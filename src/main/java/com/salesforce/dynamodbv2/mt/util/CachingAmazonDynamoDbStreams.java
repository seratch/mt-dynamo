package com.salesforce.dynamodbv2.mt.util;

import static com.amazonaws.services.dynamodbv2.model.ShardIteratorType.AFTER_SEQUENCE_NUMBER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getLast;
import static java.math.BigInteger.ONE;
import static java.util.stream.Collectors.toList;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBStreams;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBStreamsClient;
import com.amazonaws.services.dynamodbv2.model.GetRecordsRequest;
import com.amazonaws.services.dynamodbv2.model.GetRecordsResult;
import com.amazonaws.services.dynamodbv2.model.GetShardIteratorRequest;
import com.amazonaws.services.dynamodbv2.model.GetShardIteratorResult;
import com.amazonaws.services.dynamodbv2.model.LimitExceededException;
import com.amazonaws.services.dynamodbv2.model.Record;
import com.amazonaws.services.dynamodbv2.model.ShardIteratorType;
import com.salesforce.dynamodbv2.mt.mappers.DelegatingAmazonDynamoDbStreams;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A streams adapter that bins and caches records of the underlying stream to allow for multiple readers to access the
 * stream. Clients generally need to read roughly the same area of the same shards at any given time for caching to be
 * effective. Lack of locality will likely result in cache misses, which in turn requires reading the underlying stream
 * which is slower and may result in throttling when DynamoDB's limit is exceeded (each shard is limited to 5 reads
 * &amp; 2 MB per second).
 *
 * <p>Current implementation maintains the following invariants about the records
 * cache:
 * <ol>
 * <li>All cached segments contain at least one record (no empty segments)</li>
 * <li>All records are cached in at most one segment (no overlapping segments)</li>
 * </ol>
 *
 * <p>Some things we may want to improve in the future:
 * <ol>
 * <li>Reduce lock contention: avoid locking all streams/shards when adding segment</li>
 * <li>Lock shard when loading records to avoid hitting throttling</li>
 * <li>Merge small adjacent segments to avoid cache fragmentation and reduce client calls</li>
 * <li>Revisit TRIM_HORIZON record caching (since the trim horizon changes over time)</li>
 * <li>Add support for LATEST.</li>
 * </ol>
 */
public class CachingAmazonDynamoDbStreams extends DelegatingAmazonDynamoDbStreams {

    private static final Logger LOG = LoggerFactory.getLogger(CachingAmazonDynamoDbStreams.class);

    /**
     * Replace with com.amazonaws.services.dynamodbv2.streamsadapter.utils.Sleeper when we upgrade
     */
    @FunctionalInterface
    interface Sleeper {

        void sleep(long millis);
    }

    /**
     * Builder for creating instances of caching streams.
     */
    public static class Builder {

        private static final int DEFAULT_MAX_RECORDS_CACHE_SIZE = 1000;
        private static final int DEFAULT_MAX_GET_RECORDS_RETRIES = 10;
        private static final long DEFAULT_GET_RECORDS_LIMIT_EXCEEDED_BACKOFF_IN_MILLIS = 1000L;

        private final AmazonDynamoDBStreams amazonDynamoDbStreams;
        private Sleeper sleeper;
        private int maxRecordsCacheSize = DEFAULT_MAX_RECORDS_CACHE_SIZE;
        private int maxGetRecordsRetries = DEFAULT_MAX_GET_RECORDS_RETRIES;
        private long getRecordsLimitExceededBackoffInMillis =
            DEFAULT_GET_RECORDS_LIMIT_EXCEEDED_BACKOFF_IN_MILLIS;

        public Builder(AmazonDynamoDBStreams amazonDynamoDbStreams) {
            this.amazonDynamoDbStreams = amazonDynamoDbStreams;
        }

        public Builder withSleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        public Builder withMaxRecordsCacheSize(int maxRecordsCacheSize) {
            this.maxRecordsCacheSize = maxRecordsCacheSize;
            return this;
        }

        public Builder withMaxGetRecordsRetries(int maxGetRecordsRetries) {
            this.maxGetRecordsRetries = maxGetRecordsRetries;
            return this;
        }

        public Builder withGetRecordsLimitExceededBackoffInMillis(long getRecordsLimitExceededBackoffInMillis) {
            this.getRecordsLimitExceededBackoffInMillis = getRecordsLimitExceededBackoffInMillis;
            return this;
        }

        /**
         * Build instance using the configured properties.
         *
         * @return a newly created {@code CachingAmazonDynamoDbStreams} based on the contents of the {@code Builder}
         */
        public CachingAmazonDynamoDbStreams build() {
            if (sleeper == null) {
                sleeper = millis -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException ie) {
                        LOG.debug("Sleeper sleep  was interrupted ", ie);
                        Thread.currentThread().interrupt();
                    }
                };
            }
            return new CachingAmazonDynamoDbStreams(
                amazonDynamoDbStreams,
                maxRecordsCacheSize,
                maxGetRecordsRetries,
                getRecordsLimitExceededBackoffInMillis,
                sleeper);
        }
    }

    private static BigInteger parseSequenceNumber(String sequenceNumber) {
        try {
            return new BigInteger(sequenceNumber);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static BigInteger parseSequenceNumber(Record record) {
        return parseSequenceNumber(record.getDynamodb().getSequenceNumber());
    }

    /**
     * Iterator position is a sequence number in a stream shard.
     */
    private static class IteratorPosition implements Comparable<IteratorPosition>, Predicate<Record> {

        private final String streamArn;
        private final String shardId;
        private final BigInteger sequenceNumber;

        IteratorPosition(String streamArn, String shardId, BigInteger sequenceNumber) {
            this.streamArn = checkNotNull(streamArn);
            this.shardId = checkNotNull(shardId);
            this.sequenceNumber = checkNotNull(sequenceNumber);
        }

        /**
         * Checks whether the given iterator position is for the same stream and shard.
         *
         * @param o Other iterator position.
         * @return true if the other iterator position is for the same stream and shard.
         */
        boolean equalsShard(IteratorPosition o) {
            return streamArn.equals(o.streamArn) && shardId.equals(o.shardId);
        }

        @Override
        public boolean test(Record record) {
            return precedes(record);
        }

        /**
         * Checks if this iterator is before or at the given record position in the shard. Note: the client is
         * responsible for making sure the record is from the same stream and shard; records do not carry shard
         * information, so the implementation cannot verify they match. The behavior of comparing iterator positions and
         * records from different shards in unspecified.
         *
         * @param record Record to check.
         * @return true if the sequence number of the record is greater or equal to the position sequence number.
         */
        boolean precedes(Record record) {
            return sequenceNumber.compareTo(parseSequenceNumber(record)) <= 0;
        }

        boolean precedesAny(GetRecordsResult result) {
            assert !result.getRecords().isEmpty();
            return precedes(getLast(result.getRecords()));
        }

        IteratorPosition next() {
            return new IteratorPosition(streamArn, shardId, sequenceNumber.add(ONE));
        }

        IteratorPosition nextAfterLastRecord(GetRecordsResult result) {
            return new IteratorPosition(streamArn, shardId, parseSequenceNumber(getLast(result.getRecords()))).next();
        }

        @Override
        public int compareTo(@Nonnull IteratorPosition o) {
            int c = streamArn.compareTo(o.streamArn);
            if (c != 0) {
                return c;
            }
            c = shardId.compareTo(o.shardId);
            if (c != 0) {
                return c;
            }
            return sequenceNumber.compareTo(o.sequenceNumber);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            IteratorPosition that = (IteratorPosition) o;
            return Objects.equals(streamArn, that.streamArn)
                && Objects.equals(shardId, that.shardId)
                && Objects.equals(sequenceNumber, that.sequenceNumber);
        }

        @Override
        public int hashCode() {
            return Objects.hash(streamArn, shardId, sequenceNumber);
        }

    }


    /**
     * A logical shard iterator that optionally wraps an underlying DynamoDB iterator.
     */
    private static class ShardIterator {

        private static final CompositeStrings compositeStrings = new CompositeStrings('/', '\\');

        /**
         * Returns an iterator for the given request and optional DynamoDB iterator.
         *
         * @param request Iterator request.
         * @param dynamoDbIterator DynamoDB iterator (optional).
         * @return Logical shard iterator.
         */
        static ShardIterator fromRequest(GetShardIteratorRequest request, @Nullable String dynamoDbIterator) {
            return new ShardIterator(
                request.getStreamArn(),
                request.getShardId(),
                ShardIteratorType.fromValue(request.getShardIteratorType()),
                request.getSequenceNumber(),
                dynamoDbIterator
            );
        }

        // TODO pick better serialization format
        static ShardIterator fromExternalString(String value) {
            Function<String, String> f = s -> "null".equals(s) ? null : s;
            Iterator<String> it = compositeStrings.split(value);
            return new ShardIterator(
                it.next(),
                it.next(),
                ShardIteratorType.fromValue(it.next()),
                f.apply(it.next()),
                f.apply(it.next())
            );
        }

        @Nonnull
        private final String streamArn;
        @Nonnull
        private final String shardId;
        @Nonnull
        private final ShardIteratorType type;
        @Nullable
        private final String sequenceNumber;
        @Nullable
        private final BigInteger parsedSequenceNumber;
        @Nullable
        private String dynamoDbIterator;

        private ShardIterator(
            @Nonnull String streamArn,
            @Nonnull String shardId,
            @Nonnull ShardIteratorType type,
            @Nullable String sequenceNumber,
            @Nullable String dynamoDbIterator) {
            this.streamArn = checkNotNull(streamArn);
            this.shardId = checkNotNull(shardId);
            this.type = type;
            this.dynamoDbIterator = dynamoDbIterator;

            switch (type) {
                case TRIM_HORIZON:
                case LATEST:
                    checkArgument(sequenceNumber == null);
                    this.sequenceNumber = null;
                    this.parsedSequenceNumber = null;
                    break;
                case AT_SEQUENCE_NUMBER:
                case AFTER_SEQUENCE_NUMBER:
                    checkArgument(sequenceNumber != null);
                    this.sequenceNumber = sequenceNumber;
                    this.parsedSequenceNumber = parseSequenceNumber(sequenceNumber);
                    break;
                default:
                    throw new RuntimeException("Missing case statement for ShardIteratorType");
            }
        }

        /**
         * Returns a new iterator with the given dynamoDbIterator.
         *
         * @param dynamoDbIterator DynamoDb iterator.
         * @return New iterator.
         */
        ShardIterator withDynamoDbIterator(String dynamoDbIterator) {
            this.dynamoDbIterator = dynamoDbIterator;
            return this;
        }

        /**
         * Returns underlying streams iterator, loading it if present.
         *
         * @param streams Streams instance to use for loading iterator if needed.
         * @return DynamoDB iterator.
         */
        String getDynamoDbIterator(AmazonDynamoDBStreams streams) {
            if (dynamoDbIterator == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("getRecords loading DynamoDB iterator: iterator={}", this);
                }
                dynamoDbIterator = streams.getShardIterator(toRequest()).getShardIterator();
            }
            return dynamoDbIterator;
        }

        /**
         * Iterators that point to a specific sequence number (<code>AT_SEQUENCE_NUMBER</code> and
         * <code>AFTER_SEQUENCE_NUMBER</code>) can be resolved directly to a position in the stream shard without
         * additional context. We refer to these types of iterators <i>immutable</i> or <i>absolute</i>, since their
         * position in the stream shard does not change as records are added to or removed from the shard. Iterators
         * that refer to logical positions in the shard (<code>TRIM_HORIZON</code> and <code>LATEST</code>) cannot be
         * resolved directly to a position, since their position changes based on the records in the underlying stream
         * shard.
         *
         * @return position for immutable iterators, empty otherwise
         */
        Optional<IteratorPosition> resolvePosition() {
            switch (type) {
                case TRIM_HORIZON:
                case LATEST:
                    return Optional.empty();
                case AT_SEQUENCE_NUMBER:
                    return Optional.of(new IteratorPosition(streamArn, shardId, parsedSequenceNumber));
                case AFTER_SEQUENCE_NUMBER:
                    return Optional.of(new IteratorPosition(streamArn, shardId, parsedSequenceNumber.add(ONE)));
                default:
                    throw new RuntimeException("Unhandled switch case");
            }
        }

        /**
         * Resolves the position of this iterator relative to the first record returned by a query using this iterator.
         * If the iterator is absolute, its position is returned. Otherwise the position of the given record in the
         * shard is returned.
         *
         * @param firstReturnedRecord First record returned for the given iterator by the underlying stream.
         * @return Iterator position.
         */
        IteratorPosition resolvePosition(Record firstReturnedRecord) {
            return resolvePosition()
                .orElseGet(() -> new IteratorPosition(streamArn, shardId, parseSequenceNumber(firstReturnedRecord)));
        }

        /**
         * Returns a new virtual shard iterator that starts at the sequence number immediately after the last record in
         * the given records list.
         *
         * @param records Non-empty records list.
         * @return New shard iterator that starts after the last record in the list.
         */
        ShardIterator afterLast(List<Record> records) {
            assert !records.isEmpty();
            return new ShardIterator(streamArn, shardId, AFTER_SEQUENCE_NUMBER,
                getLast(records).getDynamodb().getSequenceNumber(), null);
        }

        /**
         * Returns an iterator request that can be used to retrieve an iterator from DynamoDB.
         *
         * @return Iterator request.
         */
        GetShardIteratorRequest toRequest() {
            return new GetShardIteratorRequest()
                .withStreamArn(streamArn)
                .withShardId(shardId)
                .withShardIteratorType(type)
                .withSequenceNumber(sequenceNumber);
        }

        // TODO pick better serialization format
        String toExternalString() {
            List<String> fields = new ArrayList<>(4);
            fields.add(streamArn);
            fields.add(shardId);
            fields.add(type.toString());
            fields.add(sequenceNumber == null ? "null" : sequenceNumber);
            fields.add(dynamoDbIterator == null ? "null" : dynamoDbIterator);
            return compositeStrings.join(fields);
        }

        @Override
        public String toString() {
            return String.join("/", streamArn, shardId, type.toString(), sequenceNumber);
        }
    }

    private static String toShortString(GetRecordsResult result) {
        List<Record> records = result.getRecords();
        String nextIterator = result.getNextShardIterator();
        if (records.isEmpty()) {
            return String.format("{records.size=0, nextIterator=%s}", nextIterator);
        } else {
            return String.format("{records.size=%d, first.sn=%s, last.sn=%s, nextIterator=%s",
                records.size(), records.get(0).getDynamodb().getSequenceNumber(),
                getLast(records).getDynamodb().getSequenceNumber(), nextIterator);
        }
    }

    // cache values are quasi-immutable
    private final NavigableMap<IteratorPosition, GetRecordsResult> recordsCache;
    private final Deque<IteratorPosition> evictionDeque;
    private final ReadWriteLock recordsCacheLock;
    private final int maxRecordsCacheSize;
    private final int maxGetRecordsRetries;
    private final long getRecordsLimitExceededBackoffInMillis;
    private final Sleeper sleeper;

    private CachingAmazonDynamoDbStreams(AmazonDynamoDBStreams amazonDynamoDbStreams,
        int maxRecordCacheSize,
        int maxGetRecordsRetries,
        long getRecordsLimitExceededBackoffInMillis,
        Sleeper sleeper) {
        super(amazonDynamoDbStreams);
        this.maxRecordsCacheSize = maxRecordCacheSize;
        this.maxGetRecordsRetries = maxGetRecordsRetries;
        this.sleeper = sleeper;
        this.getRecordsLimitExceededBackoffInMillis = getRecordsLimitExceededBackoffInMillis;
        this.recordsCache = new TreeMap<>();
        this.evictionDeque = new LinkedList<>();
        this.recordsCacheLock = new ReentrantReadWriteLock();
    }

    @Override
    public GetShardIteratorResult getShardIterator(GetShardIteratorRequest request) {
        // We only retrieve an actual stream iterator for mutable types (LATEST and TRIM_HORIZON). For immutable
        // iterators (AT|AFTER_SEQUENCE_NUMBER) we retrieve stream iterators lazily, since we may not need one at all
        // if we have a records cache hit. We cannot be lazy for mutable iterators, since that may violate client
        // expectations: for example, if a client gets a LATEST shard iterator, then inserts items, and then gets
        // records, it expects to (eventually) see them. If we were to defer looking up the iterator until get records
        // is called, LATEST will resolve to a different position in the stream, so the client would not see records for
        // the items that were inserted. In either case we include the original request in the iterator we return such
        // that getRecords can parse it, so that we can cache the get records result (without the request context, we
        // would not know which stream, shard, and position we retrieved records for).
        String dynamoDbIterator;
        switch (ShardIteratorType.fromValue(request.getShardIteratorType())) {
            case TRIM_HORIZON:
            case LATEST:
                dynamoDbIterator = dynamoDbStreams.getShardIterator(request).getShardIterator();
                break;
            case AT_SEQUENCE_NUMBER:
            case AFTER_SEQUENCE_NUMBER:
                // TODO add 15 min timeout to lazy iterators?
                dynamoDbIterator = null;
                break;
            default:
                throw new RuntimeException("Missing switch case on ShardIteratorType");
        }

        ShardIterator iterator = ShardIterator.fromRequest(request, dynamoDbIterator);
        return new GetShardIteratorResult().withShardIterator(iterator.toExternalString());
    }

    @Override
    public GetRecordsResult getRecords(GetRecordsRequest request) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("getRecords request={}", request);
        }

        // parse iterator
        final ShardIterator iterator = ShardIterator.fromExternalString(request.getShardIterator());

        // fetch records using cache
        final GetRecordsResult loadedResult = getRecords(iterator);

        // apply limit if applicable
        final GetRecordsResult result = applyLimit(request.getLimit(), iterator, loadedResult);

        if (LOG.isDebugEnabled()) {
            LOG.debug("getRecords result={}", toShortString(result));
        }

        return result;
    }

    /**
     * Gets records for the given shard iterator position using the record and iterator cache.
     *
     * @param iterator Position in the a given stream shard for which to retrieve records
     * @return Results loaded from the cache or underlying stream
     */
    private GetRecordsResult getRecords(ShardIterator iterator) {
        int getRecordsRetries = 0;
        while (getRecordsRetries < maxGetRecordsRetries) {
            // if iterator is resolvable, try to lookup records in cache
            Optional<GetRecordsResult> cached = iterator.resolvePosition().flatMap(this::getFromCache);
            if (cached.isPresent()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("getRecords cache hit: iterator={}, result={}", iterator, toShortString(cached.get()));
                }
                return cached.get();
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("getRecords cache miss: iterator={}", iterator);
                }
            }

            // If we have a cache miss, get DynamoDB iterator (load if needed)
            final String shardIterator = iterator.getDynamoDbIterator(dynamoDbStreams);

            // next load records from stream
            final GetRecordsResult loadedRecordsResult;
            try {
                loadedRecordsResult = dynamoDbStreams.getRecords(
                    new GetRecordsRequest().withShardIterator(shardIterator));
            } catch (LimitExceededException e) {
                long backoff = (getRecordsRetries + 1) * getRecordsLimitExceededBackoffInMillis;
                if (LOG.isWarnEnabled()) {
                    LOG.warn("getRecords limit exceeded: iterator={}, retry attempt={}, backoff={}.", iterator,
                        getRecordsRetries, backoff);
                }
                sleeper.sleep(backoff);
                getRecordsRetries++;
                continue;
            } // could catch ExpiredIteratorException and automatically renew shard iterators in cached results

            if (LOG.isDebugEnabled()) {
                LOG.debug("getRecords loaded records: iterator={}, result={}", iterator,
                    toShortString(loadedRecordsResult));
            }

            // if we didn't load anything, return without adding cache segment (preserves non-empty range invariant)
            // could cache empty results for (short) time period to avoid having every client hit the stream.
            if (loadedRecordsResult.getRecords().isEmpty()) {
                if (loadedRecordsResult.getNextShardIterator() == null) {
                    return loadedRecordsResult;
                }
                // replace with loaded iterator, so it is used to proceed through stream on next call
                return new GetRecordsResult()
                    .withRecords(loadedRecordsResult.getRecords())
                    .withNextShardIterator(
                        iterator.withDynamoDbIterator(loadedRecordsResult.getNextShardIterator()).toExternalString());
            }

            // otherwise (if we found records), try to update the cache
            Optional<GetRecordsResult> cachedResult;
            GetRecordsResult result;
            final Lock writeLock = recordsCacheLock.writeLock();
            writeLock.lock();
            try {
                // Resolve iterator position (either to sequence number it specifies or first record sequence number)
                IteratorPosition loadedPosition = iterator.resolvePosition(loadedRecordsResult.getRecords().get(0));

                // Add retrieved records to cache under that position
                cachedResult = addToCache(loadedPosition, loadedRecordsResult);

                // now lookup result: may not be exactly what we loaded if we merged result with other segments.
                result = getFromCache(loadedPosition).get();
            } finally {
                writeLock.unlock();
            }

            // log cache  outside of critical section
            if (LOG.isDebugEnabled()) {
                LOG.debug("getRecords cached result={}", cachedResult);
            }

            return result;
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("GetRecords exceeded maximum number of retries");
        }
        throw new LimitExceededException("Exhausted GetRecords retry limit.");
    }

    /**
     * Looks up cached result for given position. Acquires read lock to access cache, but may be called with read or
     * write lock held, since lock is reentrant.
     *
     * @param position Iterator for which to retrieve matching records from the cache
     * @return List of matching (i.e., immediately succeeding iterator) cached records or empty list if none match
     */
    private Optional<GetRecordsResult> getFromCache(IteratorPosition position) {
        final Lock readLock = recordsCacheLock.readLock();
        readLock.lock();
        try {
            final Optional<GetRecordsResult> cachedRecordsResult;
            final Map.Entry<IteratorPosition, GetRecordsResult> previousCacheEntry = recordsCache.floorEntry(position);
            if (previousCacheEntry == null) {
                // no matching cache entry found
                cachedRecordsResult = Optional.empty();
            } else {
                IteratorPosition previousPosition = previousCacheEntry.getKey();
                GetRecordsResult previousResult = previousCacheEntry.getValue();
                if (position.equals(previousPosition)) {
                    // exact iterator hit (hopefully common case), return all cached records
                    cachedRecordsResult = Optional.of(previousResult);
                } else if (position.equalsShard(previousPosition) && position.precedesAny(previousResult)) {
                    // Cache entry contains records that match (i.e., come after) the requested iterator
                    // position: Filter cached records to those that match. Return only that subset, to increase
                    // the chance of using a shared iterator position on the next getRecords call.
                    final List<Record> matchingCachedRecords = previousResult.getRecords().stream()
                        .filter(position)
                        .collect(toList());
                    return Optional.of(new GetRecordsResult()
                        .withRecords(matchingCachedRecords)
                        .withNextShardIterator(previousResult.getNextShardIterator()));
                } else {
                    // no cached records in the preceding cache entry match the requested position (i.e., all records
                    // precede it)
                    cachedRecordsResult = Optional.empty();
                }
            }
            return cachedRecordsResult;
        } finally {
            readLock.unlock();
        }
    }

    private Optional<GetRecordsResult> addToCache(IteratorPosition loadedPosition, GetRecordsResult loadedResult) {
        final Lock writeLock = recordsCacheLock.writeLock();
        writeLock.lock();
        try {
            IteratorPosition cachePosition = loadedPosition;
            GetRecordsResult cacheResult = new GetRecordsResult()
                .withRecords(loadedResult.getRecords())
                .withNextShardIterator(loadedResult.getNextShardIterator() == null ? null
                    : new ShardIterator(loadedPosition.streamArn, loadedPosition.shardId, AFTER_SEQUENCE_NUMBER,
                        getLast(loadedResult.getRecords()).getDynamodb().getSequenceNumber(),
                        loadedResult.getNextShardIterator()).toExternalString());

            boolean predecessorAdjacent = false;
            final Entry<IteratorPosition, GetRecordsResult> predecessor = recordsCache.floorEntry(loadedPosition);
            if (predecessor != null && loadedPosition.equalsShard(predecessor.getKey())) {
                GetRecordsResult predecessorResult = predecessor.getValue();
                if (loadedPosition.precedesAny(predecessorResult)) {
                    // the previous cache entry overlaps with the records we retrieved: filter out overlapping records
                    // (by reducing the loaded records to those that come after the last predecessor record)
                    cachePosition = loadedPosition.nextAfterLastRecord(predecessorResult);
                    cacheResult.setRecords(cacheResult.getRecords().stream()
                        .filter(cachePosition)
                        .collect(toList()));
                    // if all retrieved records are contained in the predecessor, we have nothing to add
                    if (cacheResult.getRecords().isEmpty()) {
                        return Optional.empty();
                    }
                    predecessorAdjacent = true;
                } else {
                    predecessorAdjacent = loadedPosition.equals(loadedPosition.nextAfterLastRecord(predecessorResult));
                }
            }

            boolean successorAdjacent = false;
            final Entry<IteratorPosition, GetRecordsResult> successor = recordsCache.higherEntry(cachePosition);
            if (successor != null && cachePosition.equalsShard(successor.getKey())) {
                IteratorPosition successorPosition = successor.getKey();
                if (successorPosition.precedesAny(cacheResult)) {
                    // the succeeding cache entry overlaps with loaded records: filter out overlapping records
                    // (by reducing the loaded records to those that come before the successor starting position)
                    cacheResult.setRecords(cacheResult.getRecords().stream()
                        .filter(successorPosition.negate())
                        .collect(toList()));

                    if (cacheResult.getRecords().isEmpty()) {
                        // if all retrieved records are contained in the successor, reindex (and maybe merge) successor
                        recordsCache.remove(successorPosition);
                        cacheResult = successor.getValue();
                        successorAdjacent = false;
                    } else {
                        // if some of the retrieved records are not contained in the next segment,
                        cacheResult.setNextShardIterator(
                            new ShardIterator(cachePosition.streamArn, cachePosition.shardId, AFTER_SEQUENCE_NUMBER,
                                getLast(loadedResult.getRecords()).getDynamodb().getSequenceNumber(), null)
                                .toExternalString());
                        successorAdjacent = true;
                    }
                } else {
                    successorAdjacent = successorPosition.equals(cachePosition.nextAfterLastRecord(cacheResult));
                }
            }

            if (predecessorAdjacent) {
                int totalSize = predecessor.getValue().getRecords().size() + cacheResult.getRecords().size();
                // TODO find constant for max result size
                if (totalSize <= 1000) {
                    List<Record> mergedRecords = new ArrayList<>(totalSize);
                    mergedRecords.addAll(predecessor.getValue().getRecords());
                    mergedRecords.addAll(cacheResult.getRecords());
                    cacheResult.setRecords(mergedRecords);
                    recordsCache.remove(predecessor.getKey());
                }
            }
            if (successorAdjacent) {
                int totalSize = cacheResult.getRecords().size() + successor.getValue().getRecords().size();
                if (totalSize <= 1000) {
                    List<Record> mergedRecords = new ArrayList<>(totalSize);
                    mergedRecords.addAll(cacheResult.getRecords());
                    mergedRecords.addAll(successor.getValue().getRecords());
                    cacheResult.setRecords(mergedRecords);
                    cacheResult.setNextShardIterator(successor.getValue().getNextShardIterator());
                    recordsCache.remove(successor.getKey());
                }
            }

            recordsCache.put(cachePosition, cacheResult);
            evictionDeque.add(cachePosition);
            while (recordsCache.size() > maxRecordsCacheSize) {
                recordsCache.remove(evictionDeque.remove());
            }

            return Optional.of(cacheResult);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Reduces the result based on the limit if present.
     *
     * @param limit Limit specified in the request
     * @param iterator Iterator specified in the request
     * @param loadedResult Loaded result to limit
     * @return Result that is limited to the number of records specified in the request
     */
    private GetRecordsResult applyLimit(Integer limit, ShardIterator iterator, GetRecordsResult loadedResult) {
        checkArgument(limit == null || limit > 0);
        final GetRecordsResult result;
        if (limit == null || limit >= loadedResult.getRecords().size()) {
            result = loadedResult;
        } else {
            List<Record> records = loadedResult.getRecords().subList(0, limit);
            result = new GetRecordsResult()
                .withRecords(records)
                .withNextShardIterator(iterator.afterLast(records).toExternalString());
        }
        return result;
    }


}