package com.linkedin.identity.rest.resources;

import com.linkedin.common.urn.CorpuserUrn;
import com.linkedin.identity.CorpUser;
import com.linkedin.identity.CorpUserEditableInfo;
import com.linkedin.identity.CorpUserInfo;
import com.linkedin.identity.CorpUserKey;
import com.linkedin.metadata.aspect.CorpUserAspect;
import com.linkedin.metadata.dao.BaseLocalDAO;
import com.linkedin.metadata.dao.BaseSearchDAO;
import com.linkedin.metadata.dao.utils.ModelUtils;
import com.linkedin.metadata.query.Filter;
import com.linkedin.metadata.query.SearchResultMetadata;
import com.linkedin.metadata.restli.BaseSearchableEntityResource;
import com.linkedin.metadata.search.CorpUserInfoDocument;
import com.linkedin.metadata.snapshot.CorpUserSnapshot;
import com.linkedin.parseq.Task;
import com.linkedin.restli.common.ComplexResourceKey;
import com.linkedin.restli.common.EmptyRecord;
import com.linkedin.restli.server.CollectionResult;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.annotations.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.linkedin.metadata.restli.RestliConstants.*;

@RestLiCollection(name = "corpUsers", namespace = "com.linkedin.identity", keyName = "corpUser")
public final class CorpUsers extends BaseSearchableEntityResource<
        // @formatter:off
        CorpUserKey,
        CorpUser,
        CorpuserUrn,
        CorpUserSnapshot,
        CorpUserAspect,
        CorpUserInfoDocument> {
        // @formatter:on

    @Inject
    @Named("corpUserDao")
    private BaseLocalDAO _localDAO;

    @Inject
    @Named("corpUserSearchDao")
    private BaseSearchDAO _esSearchDAO;

    public CorpUsers() {
        super(CorpUserSnapshot.class, CorpUserAspect.class);
    }

    @Override
    @Nonnull
    protected BaseLocalDAO getLocalDAO() {
        return _localDAO;
    }

    @Override
    @Nonnull
    protected BaseSearchDAO getSearchDAO() {
        return _esSearchDAO;
    }

    @Override
    @Nonnull
    protected CorpuserUrn toUrn(@Nonnull CorpUserKey key) {
        return new CorpuserUrn(key.getName());
    }

    @Override
    @Nonnull
    protected CorpUserKey toKey(@Nonnull CorpuserUrn urn) {
        return new CorpUserKey().setName(urn.getUsernameEntity());
    }

    @Override
    @Nonnull
    protected CorpUser toValue(@Nonnull CorpUserSnapshot snapshot) {
        final CorpUser value = new CorpUser().setUsername(snapshot.getUrn().getUsernameEntity());
        ModelUtils.getAspectsFromSnapshot(snapshot).forEach(aspect -> {
            if (aspect instanceof CorpUserInfo) {
                value.setInfo(CorpUserInfo.class.cast(aspect));
            } else if (aspect instanceof CorpUserEditableInfo) {
                value.setEditableInfo(CorpUserEditableInfo.class.cast(aspect));
            }
        });
        return value;
    }

    @RestMethod.GetAll
    @Nonnull
    public Task<List<CorpUser>> getAll(@QueryParam(PARAM_ASPECTS) @Optional("[]") @Nonnull String[] aspectNames,
                                       @QueryParam(PARAM_FILTER) @Optional @Nullable Filter filter) {
        return super.getAll(aspectNames, filter);
    }

    @Override
    @Nonnull
    protected CorpUserSnapshot toSnapshot(@Nonnull CorpUser corpUser, @Nonnull CorpuserUrn corpuserUrn) {
        final List<CorpUserAspect> aspects = new ArrayList<>();
        if (corpUser.hasInfo()) {
            aspects.add(ModelUtils.newAspectUnion(CorpUserAspect.class, corpUser.getInfo()));
        }
        if (corpUser.hasEditableInfo()) {
            aspects.add(ModelUtils.newAspectUnion(CorpUserAspect.class, corpUser.getEditableInfo()));
        }
        return ModelUtils.newSnapshot(CorpUserSnapshot.class, corpuserUrn, aspects);
    }

    @RestMethod.Get
    @Override
    @Nonnull
    public Task<CorpUser> get(@Nonnull ComplexResourceKey<CorpUserKey, EmptyRecord> key,
                              @QueryParam(PARAM_ASPECTS) @Optional("[]") String[] aspectNames) {
        return super.get(key, aspectNames);
    }

    @RestMethod.BatchGet
    @Override
    @Nonnull
    public Task<Map<ComplexResourceKey<CorpUserKey, EmptyRecord>, CorpUser>> batchGet(
            @Nonnull Set<ComplexResourceKey<CorpUserKey, EmptyRecord>> keys,
            @QueryParam(PARAM_ASPECTS) @Optional("[]") String[] aspectNames) {
        return super.batchGet(keys, aspectNames);
    }

    @Finder(FINDER_SEARCH)
    @Override
    @Nonnull
    public Task<CollectionResult<CorpUser, SearchResultMetadata>> search(@QueryParam(PARAM_INPUT) @Nonnull String input,
                                                                         @QueryParam(PARAM_ASPECTS) @Optional("[]") @Nonnull String[] aspectNames,
                                                                         @QueryParam(PARAM_FILTER) @Optional @Nullable Filter filter,
                                                                         @PagingContextParam @Nonnull PagingContext pagingContext) {
        return super.search(input, aspectNames, filter, pagingContext);
    }
}