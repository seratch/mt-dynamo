package com.salesforce.dynamodbv2;

import static com.salesforce.dynamodbv2.testsupport.DefaultTestSetup.TABLE1;
import static com.salesforce.dynamodbv2.testsupport.DefaultTestSetup.TABLE2;
import static com.salesforce.dynamodbv2.testsupport.DefaultTestSetup.TABLE3;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.HASH_KEY_FIELD;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.HASH_KEY_VALUE;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.RANGE_KEY_STRING_VALUE;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.SOME_FIELD;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.SOME_FIELD_VALUE;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.buildHkKey;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.buildHkRkItemWithSomeFieldValue;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.buildHkRkKey;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.buildItemWithSomeFieldValue;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.getItem;
import static com.salesforce.dynamodbv2.testsupport.TestSupport.getItemDefaultHkRk;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.google.common.collect.ImmutableMap;
import com.salesforce.dynamodbv2.mt.context.MtAmazonDynamoDbContextProvider;
import com.salesforce.dynamodbv2.testsupport.ArgumentBuilder;
import com.salesforce.dynamodbv2.testsupport.ArgumentBuilder.TestArgument;
import com.salesforce.dynamodbv2.testsupport.DefaultArgumentProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

/**
 * Tests deleteItem().
 *
 * @author msgroi
 */
class DeleteTest {

    private static final MtAmazonDynamoDbContextProvider MT_CONTEXT = ArgumentBuilder.MT_CONTEXT;

    @ParameterizedTest(name = "{arguments}")
    @ArgumentsSource(DefaultArgumentProvider.class)
    void delete(TestArgument testArgument) {
        String org = testArgument.getOrgs().get(0);
        MT_CONTEXT.setContext(org);
        Map<String, AttributeValue> deleteItemKey = buildHkKey(testArgument.getHashKeyAttrType());
        final Map<String, AttributeValue> originalDeleteItemKey = new HashMap<>(deleteItemKey);
        DeleteItemRequest deleteItemRequest = new DeleteItemRequest().withTableName(TABLE1).withKey(deleteItemKey);
        testArgument.getAmazonDynamoDb().deleteItem(deleteItemRequest);
        assertNull(getItem(testArgument.getHashKeyAttrType(),
                   testArgument.getAmazonDynamoDb(), TABLE1, HASH_KEY_VALUE));
        assertEquals(TABLE1, deleteItemRequest.getTableName()); // assert no side effects
        assertThat(deleteItemRequest.getKey(), is(originalDeleteItemKey)); // assert no side effects
        assertThat(buildItemWithSomeFieldValue(testArgument.getHashKeyAttrType(),
                                        SOME_FIELD_VALUE + TABLE2 + org),
                   is(getItem(testArgument.getHashKeyAttrType(),
                              testArgument.getAmazonDynamoDb(),
                              TABLE2))); // assert different table, same org
        testArgument.getOrgs().stream().filter(otherOrg -> !otherOrg.equals(org)).forEach(otherOrg -> {
            MT_CONTEXT.setContext(otherOrg);
            // assert same table, different orgs
            assertThat(buildItemWithSomeFieldValue(testArgument.getHashKeyAttrType(),
                                             SOME_FIELD_VALUE + TABLE1 + otherOrg),
                       is(getItem(testArgument.getHashKeyAttrType(), testArgument.getAmazonDynamoDb(), TABLE1)));
        });
    }

    @ParameterizedTest(name = "{arguments}")
    @ArgumentsSource(DefaultArgumentProvider.class)
    void deleteWithAttributeExists(TestArgument testArgument) {
        MT_CONTEXT.setContext(testArgument.getOrgs().get(0));
        testArgument.getAmazonDynamoDb().deleteItem(new DeleteItemRequest()
            .withTableName(TABLE1).withKey(buildHkKey(testArgument.getHashKeyAttrType()))
            .withConditionExpression("attribute_exists(#field)")
            .withExpressionAttributeNames(ImmutableMap.of("#field", SOME_FIELD)));
        assertNull(getItem(testArgument.getHashKeyAttrType(),
            testArgument.getAmazonDynamoDb(), TABLE1, HASH_KEY_VALUE));
    }

    @ParameterizedTest(name = "{arguments}")
    @ArgumentsSource(DefaultArgumentProvider.class)
    void deleteWithAttributeExistsWithLiterals(TestArgument testArgument) {
        MT_CONTEXT.setContext(testArgument.getOrgs().get(0));
        testArgument.getAmazonDynamoDb().deleteItem(new DeleteItemRequest()
            .withTableName(TABLE1).withKey(buildHkKey(testArgument.getHashKeyAttrType()))
            .withConditionExpression("attribute_exists(" + SOME_FIELD + ")"));
        assertNull(getItem(testArgument.getHashKeyAttrType(),
            testArgument.getAmazonDynamoDb(), TABLE1, HASH_KEY_VALUE));
    }

    @ParameterizedTest(name = "{arguments}")
    @ArgumentsSource(DefaultArgumentProvider.class)
    void deleteWithAttributeExistsFails(TestArgument testArgument) {
        MT_CONTEXT.setContext(testArgument.getOrgs().get(0));
        try {
            testArgument.getAmazonDynamoDb().deleteItem(new DeleteItemRequest()
                .withTableName(TABLE1).withKey(buildHkKey(testArgument.getHashKeyAttrType()))
                .withConditionExpression("attribute_exists(#field)")
                .withExpressionAttributeNames(ImmutableMap.of("#field", "someNonExistentField")));
            fail("expected ConditionalCheckFailedException not encountered");
        } catch (ConditionalCheckFailedException ignore) {
            // expected
        }
    }

    @ParameterizedTest(name = "{arguments}")
    @ArgumentsSource(DefaultArgumentProvider.class)
    void deleteWithHkAttributeExists(TestArgument testArgument) {
        MT_CONTEXT.setContext(testArgument.getOrgs().get(0));
        testArgument.getAmazonDynamoDb().deleteItem(new DeleteItemRequest()
            .withTableName(TABLE1).withKey(buildHkKey(testArgument.getHashKeyAttrType()))
            .withConditionExpression("attribute_exists(#field)")
            .withExpressionAttributeNames(ImmutableMap.of("#field", HASH_KEY_FIELD)));
        assertNull(getItem(testArgument.getHashKeyAttrType(),
            testArgument.getAmazonDynamoDb(), TABLE1, HASH_KEY_VALUE));
    }

    @ParameterizedTest(name = "{arguments}")
    @ArgumentsSource(DefaultArgumentProvider.class)
    void deleteHkRkTable(TestArgument testArgument) {
        String org = testArgument.getOrgs().get(0);
        MT_CONTEXT.setContext(org);
        Map<String, AttributeValue> deleteItemKey = buildHkRkKey(testArgument.getHashKeyAttrType());
        final Map<String, AttributeValue> originalDeleteItemKey = new HashMap<>(deleteItemKey);
        DeleteItemRequest deleteItemRequest = new DeleteItemRequest().withTableName(TABLE3).withKey(deleteItemKey);
        testArgument.getAmazonDynamoDb().deleteItem(deleteItemRequest);
        assertNull(getItem(testArgument.getHashKeyAttrType(),
            testArgument.getAmazonDynamoDb(), TABLE3, HASH_KEY_VALUE, Optional.of(RANGE_KEY_STRING_VALUE)));
        assertEquals(TABLE3, deleteItemRequest.getTableName()); // assert no side effects
        assertThat(deleteItemRequest.getKey(), is(originalDeleteItemKey)); // assert no side effects
        testArgument.getOrgs().stream().filter(otherOrg -> !otherOrg.equals(org)).forEach(otherOrg -> {
            MT_CONTEXT.setContext(otherOrg);
            // assert same table, different orgs
            assertThat(buildHkRkItemWithSomeFieldValue(testArgument.getHashKeyAttrType(),
                                                 SOME_FIELD_VALUE + TABLE3 + otherOrg),
                       is(getItemDefaultHkRk(testArgument.getHashKeyAttrType(),
                               testArgument.getAmazonDynamoDb(),
                               TABLE3)));
        });
    }

}
