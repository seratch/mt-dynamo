package com.salesforce.dynamodbv2.mt.mappers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBStreams;
import com.amazonaws.services.dynamodbv2.model.Record;
import com.salesforce.dynamodbv2.mt.mappers.MtAmazonDynamoDb.MtRecord;
import com.salesforce.dynamodbv2.mt.util.StreamArn;
import java.util.function.Function;

/**
 * Table per tenant type streams implementation.
 */
class MtAmazonDynamoDbStreamsByTable extends MtAmazonDynamoDbStreamsBase<MtAmazonDynamoDbByTable> implements
    MtAmazonDynamoDbStreams {

    MtAmazonDynamoDbStreamsByTable(AmazonDynamoDBStreams streams, MtAmazonDynamoDbByTable mtDynamoDb) {
        super(streams, mtDynamoDb);
    }

    @Override
    protected Function<Record, MtRecord> getRecordMapper(StreamArn arn) {
        String[] tenantAndTableName = mtDynamoDb.getTenantAndTableName(arn.getTableName());
        return record -> mapRecord(tenantAndTableName[0], tenantAndTableName[1], record);
    }

    private MtRecord mapRecord(String tenant, String tableName, Record record) {
        return new MtRecord()
            .withAwsRegion(record.getAwsRegion())
            .withEventID(record.getEventID())
            .withEventName(record.getEventName())
            .withEventSource(record.getEventSource())
            .withEventVersion(record.getEventVersion())
            .withContext(tenant)
            .withTableName(tableName)
            .withDynamodb(record.getDynamodb());
    }

}
