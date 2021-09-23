package com.linkedin.metadata.timeseries.elastic;

import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.ByteString;
import com.linkedin.metadata.aspect.EnvelopedAspect;
import com.linkedin.metadata.dao.exception.ESQueryException;
import com.linkedin.metadata.dao.utils.ESUtils;
import com.linkedin.metadata.dao.utils.RecordUtils;
import com.linkedin.metadata.query.Condition;
import com.linkedin.metadata.query.Criterion;
import com.linkedin.metadata.search.elasticsearch.update.BulkListener;
import com.linkedin.metadata.timeseries.TimeseriesAspectService;
import com.linkedin.metadata.timeseries.elastic.indexbuilder.TimeseriesAspectIndexBuilders;
import com.linkedin.metadata.utils.elasticsearch.IndexConvention;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import com.linkedin.mxe.GenericAspect;
import com.linkedin.mxe.SystemMetadata;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;


@Slf4j
public class ElasticSearchTimeseriesAspectService implements TimeseriesAspectService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String TIMESTAMP_FIELD = "timestampMillis";

  private final IndexConvention _indexConvention;
  private final BulkProcessor _bulkProcessor;
  private final TimeseriesAspectIndexBuilders _indexBuilders;
  private final RestHighLevelClient _searchClient;

  public ElasticSearchTimeseriesAspectService(@Nonnull RestHighLevelClient searchClient,
      @Nonnull IndexConvention indexConvention, @Nonnull TimeseriesAspectIndexBuilders indexBuilders,
      int bulkRequestsLimit, int bulkFlushPeriod, int numRetries, long retryInterval) {
    _indexConvention = indexConvention;
    _indexBuilders = indexBuilders;
    _searchClient = searchClient;
    _bulkProcessor = BulkProcessor.builder(
        (request, bulkListener) -> searchClient.bulkAsync(request, RequestOptions.DEFAULT, bulkListener),
        BulkListener.getInstance())
        .setBulkActions(bulkRequestsLimit)
        .setFlushInterval(TimeValue.timeValueSeconds(bulkFlushPeriod))
        .setBackoffPolicy(BackoffPolicy.constantBackoff(TimeValue.timeValueSeconds(retryInterval), numRetries))
        .build();
  }

  private static EnvelopedAspect parseDocument(@Nonnull SearchHit doc) {
    Map<String, Object> docFields = doc.getSourceAsMap();
    EnvelopedAspect envelopedAspect = new EnvelopedAspect();
    Object event = docFields.get("event");
    GenericAspect genericAspect;
    try {
      genericAspect = new GenericAspect().setValue(
          ByteString.unsafeWrap(OBJECT_MAPPER.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)));
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize event from the timeseries aspect index: " + e);
    }
    genericAspect.setContentType("application/json");
    envelopedAspect.setAspect(genericAspect);
    Object systemMetadata = docFields.get("systemMetadata");
    if (systemMetadata != null) {
      try {
        envelopedAspect.setSystemMetadata(
            RecordUtils.toRecordTemplate(SystemMetadata.class, OBJECT_MAPPER.writeValueAsString(systemMetadata)));
      } catch (JsonProcessingException e) {
        throw new RuntimeException("Failed to deserialize system metadata from the timeseries aspect index: " + e);
      }
    }

    return envelopedAspect;
  }

  @Override
  public void configure() {
    _indexBuilders.buildAll();
  }

  @Override
  public void upsertDocument(@Nonnull String entityName, @Nonnull String aspectName, @Nonnull JsonNode document) {
    String indexName = _indexConvention.getTimeseriesAspectIndexName(entityName, aspectName);
    String docId;
    try {
      docId = toDocId(document);
    } catch (JsonProcessingException e) {
      log.error("Failed to get document ID for document: {}", document);
      return;
    }
    final IndexRequest indexRequest =
        new IndexRequest(indexName).id(docId).source(document.toString(), XContentType.JSON);
    final UpdateRequest updateRequest = new UpdateRequest(indexName, docId).doc(document.toString(), XContentType.JSON)
        .detectNoop(false)
        .upsert(indexRequest);
    _bulkProcessor.add(updateRequest);
  }

  @Override
  public List<EnvelopedAspect> getAspectValues(@Nonnull final Urn urn, @Nonnull String entityName,
      @Nonnull String aspectName, @Nullable Long startTimeMillis, @Nullable Long endTimeMillis, int limit) {
    final BoolQueryBuilder filterQueryBuilder = ESUtils.buildFilterQuery(null);
    filterQueryBuilder.must(QueryBuilders.matchQuery("urn", urn.toString()));
    if (startTimeMillis != null) {
      Criterion startTimeCriterion = new Criterion().setField(TIMESTAMP_FIELD)
          .setCondition(Condition.GREATER_THAN_OR_EQUAL_TO)
          .setValue(startTimeMillis.toString());
      filterQueryBuilder.must(ESUtils.getQueryBuilderFromCriterionForSearch(startTimeCriterion));
    }
    if (endTimeMillis != null) {
      Criterion endTimeCriterion = new Criterion().setField(TIMESTAMP_FIELD)
          .setCondition(Condition.LESS_THAN_OR_EQUAL_TO)
          .setValue(endTimeMillis.toString());
      filterQueryBuilder.must(ESUtils.getQueryBuilderFromCriterionForSearch(endTimeCriterion));
    }
    final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder.query(filterQueryBuilder);
    searchSourceBuilder.size(limit);
    searchSourceBuilder.sort(SortBuilders.fieldSort("@timestamp").order(SortOrder.DESC));

    final SearchRequest searchRequest = new SearchRequest();
    searchRequest.source(searchSourceBuilder);

    String indexName = _indexConvention.getTimeseriesAspectIndexName(entityName, aspectName);
    searchRequest.indices(indexName);

    log.debug("Search request is: " + searchRequest);
    SearchHits hits;
    try (Timer.Context ignored = MetricUtils.timer(this.getClass(), "esSearch").time()) {
      final SearchResponse searchResponse = _searchClient.search(searchRequest, RequestOptions.DEFAULT);
      hits = searchResponse.getHits();
    } catch (Exception e) {
      log.error("Search query failed:" + e.getMessage());
      throw new ESQueryException("Search query failed:", e);
    }
    return Arrays.stream(hits.getHits())
        .map(ElasticSearchTimeseriesAspectService::parseDocument)
        .collect(Collectors.toList());
  }

  private String toDocId(@Nonnull final JsonNode document) throws JsonProcessingException {
    return DigestUtils.md5Hex(String.valueOf(document.hashCode()));
  }
}
