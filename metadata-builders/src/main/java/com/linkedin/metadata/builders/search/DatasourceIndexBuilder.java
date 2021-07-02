package com.linkedin.metadata.builders.search;

import com.linkedin.common.GlobalTags;
import com.linkedin.common.GlossaryTerms;
import com.linkedin.common.Ownership;
import com.linkedin.common.Status;
import com.linkedin.common.urn.DatasourceUrn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.template.StringArray;
import com.linkedin.datasource.DatasourceDeprecation;
import com.linkedin.datasource.DatasourceProperties;
import com.linkedin.metadata.search.DatasourceDocument;
import com.linkedin.metadata.snapshot.DatasourceSnapshot;
import com.linkedin.schema.EditableSchemaFieldInfo;
import com.linkedin.schema.EditableSchemaMetadata;
import com.linkedin.schema.SchemaField;
import com.linkedin.schema.SchemaMetadata;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
public class DatasourceIndexBuilder extends BaseIndexBuilder<DatasourceDocument> {
  public DatasourceIndexBuilder() {
    super(Collections.singletonList(DatasourceSnapshot.class), DatasourceDocument.class);
  }

  @Nonnull
  public static String buildBrowsePath(@Nonnull DatasourceUrn urn) {
    return ("/" + urn.getOriginEntity() + "/" + urn.getPlatformEntity().getPlatformNameEntity() + "/"
        + urn.getDatasourceNameEntity()).replace('.', '/').toLowerCase();
  }

  /**
   * Given datasource urn, this returns a {@link DatasourceDocument} model that has urn, datasource name, platform and origin fields set
   *
   * @param urn {@link DatasourceUrn} that needs to be set
   * @return {@link DatasourceDocument} model with relevant fields set that are extracted from the urn
   */
  @Nonnull
  private static DatasourceDocument setUrnDerivedFields(@Nonnull DatasourceUrn urn) {
    return new DatasourceDocument().setName(urn.getDatasourceNameEntity())
        .setOrigin(urn.getOriginEntity())
        .setPlatform(urn.getPlatformEntity().getPlatformNameEntity())
        .setUrn(urn)
        .setBrowsePaths(new StringArray(Collections.singletonList(buildBrowsePath(urn))));
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn, @Nonnull Ownership ownership) {
    final StringArray owners = BuilderUtils.getCorpUserOwners(ownership);
    return new DatasourceDocument().setUrn(urn).setHasOwners(!owners.isEmpty()).setOwners(owners);
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn, @Nonnull Status status) {
    return new DatasourceDocument().setUrn(urn).setRemoved(status.isRemoved());
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn,
      @Nonnull DatasourceDeprecation deprecation) {
    return new DatasourceDocument().setUrn(urn).setDeprecated(deprecation.isDeprecated());
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn,
      @Nonnull DatasourceProperties datasourceProperties) {
    final DatasourceDocument doc = new DatasourceDocument().setUrn(urn);
    if (datasourceProperties.getDescription() != null) {
      doc.setDescription(datasourceProperties.getDescription());
    }
    return doc;
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn,
      @Nonnull SchemaMetadata schemaMetadata) {
    final StringArray fieldPaths = new StringArray(
        schemaMetadata.getFields().stream().map(SchemaField::getFieldPath).collect(Collectors.toList()));
    final StringArray fieldDescriptions = new StringArray(schemaMetadata.getFields()
        .stream()
        .map(SchemaField::getDescription)
        .filter(Objects::nonNull)
        .collect(Collectors.toList()));
    final StringArray fieldTags = new StringArray(schemaMetadata.getFields()
        .stream()
        .map(SchemaField::getGlobalTags)
        .filter(Objects::nonNull)
        .flatMap(globalTags -> globalTags.getTags().stream())
        .map(tag -> tag.getTag().getName())
        .collect(Collectors.toSet()));
    return new DatasourceDocument().setUrn(urn)
        .setHasSchema(true)
        .setFieldPaths(fieldPaths)
        .setFieldDescriptions(fieldDescriptions)
        .setFieldTags(fieldTags);
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn,
      @Nonnull EditableSchemaMetadata editableSchemaMetadata) {
    final StringArray fieldDescriptions = new StringArray(editableSchemaMetadata.getEditableSchemaFieldInfo()
        .stream()
        .map(EditableSchemaFieldInfo::getDescription)
        .filter(Objects::nonNull)
        .collect(Collectors.toList()));
    final StringArray fieldTags = new StringArray(editableSchemaMetadata.getEditableSchemaFieldInfo()
        .stream()
        .map(EditableSchemaFieldInfo::getGlobalTags)
        .filter(Objects::nonNull)
        .flatMap(globalTags -> globalTags.getTags().stream())
        .map(tag -> tag.getTag().getName())
        .collect(Collectors.toSet()));
    return new DatasourceDocument().setUrn(urn)
        .setEditedFieldDescriptions(fieldDescriptions)
        .setEditedFieldTags(fieldTags);
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn, @Nonnull GlobalTags globalTags) {
    return new DatasourceDocument().setUrn(urn)
        .setTags(new StringArray(
            globalTags.getTags().stream().map(tag -> tag.getTag().getName()).collect(Collectors.toList())));
  }

  @Nonnull
  private DatasourceDocument getDocumentToUpdateFromAspect(@Nonnull DatasourceUrn urn,
                                                        @Nonnull GlossaryTerms glossaryTerms) {
    return new DatasourceDocument().setUrn(urn)
            .setGlossaryTerms(new StringArray(glossaryTerms.getTerms()
                    .stream()
                    .map(term -> {
                      String name = term.getUrn().getNameEntity();
                      if (name.contains(".")) {
                        String[] nodes = name.split(Pattern.quote("."));
                        return nodes[nodes.length - 1];
                      }
                      return name;
                    }).collect(Collectors.toList())));
  }

  @Nonnull
  private List<DatasourceDocument> getDocumentsToUpdateFromSnapshotType(@Nonnull DatasourceSnapshot datasourceSnapshot) {
    final DatasourceUrn urn = datasourceSnapshot.getUrn();
    final List<DatasourceDocument> documents = datasourceSnapshot.getAspects().stream().map(aspect -> {
      if (aspect.isDatasourceDeprecation()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getDatasourceDeprecation());
      } else if (aspect.isDatasourceProperties()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getDatasourceProperties());
      } else if (aspect.isOwnership()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getOwnership());
      } else if (aspect.isSchemaMetadata()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getSchemaMetadata());
      } else if (aspect.isEditableSchemaMetadata()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getEditableSchemaMetadata());
      } else if (aspect.isStatus()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getStatus());
      } else if (aspect.isGlobalTags()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getGlobalTags());
      } else if (aspect.isGlossaryTerms()) {
        return getDocumentToUpdateFromAspect(urn, aspect.getGlossaryTerms());
      }
      return null;
    }).filter(Objects::nonNull).collect(Collectors.toList());
    documents.add(setUrnDerivedFields(urn));
    return documents;
  }

  @Override
  @Nonnull
  public final List<DatasourceDocument> getDocumentsToUpdate(@Nonnull RecordTemplate genericSnapshot) {
    if (genericSnapshot instanceof DatasourceSnapshot) {
      return getDocumentsToUpdateFromSnapshotType((DatasourceSnapshot) genericSnapshot);
    }
    return Collections.emptyList();
  }

  @Override
  @Nonnull
  public Class<DatasourceDocument> getDocumentType() {
    return DatasourceDocument.class;
  }
}