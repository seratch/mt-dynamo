# Changelog
All notable changes to this project will be documented in this file.

Multitenant AWS Dynamo supports the [AWS Dynamo Java API](http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/index.html?com/amazonaws/services/dynamodbv2/document/package-summary.html).
  
You can write your application code against the Amazon DynamoDB interface as you would for any other application.  The implementation will manage storage of data by tenant.

## 0.9.25 (November 28, 2018)

* Fixed issue by advancing trim-horizon iterator even if no records found
* Disallow *no* context (was `Optional.empty()`)&mdash;base context is now `""`; replace `MtAmazonDynamoDbContextProvider`'s one abstract method, `Optional<String> getContextOpt()`, with `String getContext()`

## 0.9.22 (November 14, 2018)

* Improved logging of table mappings in `SharedTable` implementation

## 0.9.21 (October 29, 2018)

* URL encode `context` and `tenantTableName` in multitenant prefixes

## 0.9.20 (October 19, 2018)

* Fixed `NullPointerException` in `SharedTable` when performing an update request on a GSI hash key attribute
* Fixed `UnsupportedOperationException` exception when performing an update request on a GSI hash key attribute
* Dropped support for `attributeUpdates` methods in `UpdateItemRequest` in `SharedTable`
* Streaming implementation improvements

## 0.9.19 (October 11, 2018)

* `CachingAmazonDynamoDbStreams` improvements

## 0.9.16 (September 26, 2018)

* `SharedTable` support for 'greater than' (GT) queries on tables with numeric range-key fields (via `KeyConditions` only)
* `SharedTable` support for conditional puts
* `SharedTable` fixed `TrimmedDataAccessException` for `TRIM_HORIZON` iterators

## 0.9.15 (September 24, 2018)

* Remove custom `listStreams` method and KCL dependency

## 0.9.14 (September 22, 2018)

* `ByTable` support for streams API*
* `SharedTable` support for latestStreamArn*

## 0.9.13 (September 20, 2018)

* `SharedTable` support for streams API 

## 0.9.11 (September 11, 2018) 

* `SharedTable` support for conditional updates and deletes
* Added `HybridSharedTableBuilder`

## 0.9.10 (August 16, 2018)

* Support for `batchGetItem`

## 0.9.8

* Replaced `ByIndex` implementation with `SharedTable`

## 0.9.7

* Bug fixes

## 0.9.6

* Added `listStreams()` support

## 0.9.3

* First revision
