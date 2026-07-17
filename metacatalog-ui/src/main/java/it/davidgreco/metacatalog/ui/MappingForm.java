package it.davidgreco.metacatalog.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Backing form for creating a mapping entity type relationship on the dashboard.
 *
 * <p>The {@code mappingValues} field carries the mapping expressions as a raw JSON string
 * (assembled or hand-edited in the form). The entity path references are collected as two parallel
 * lists, {@code aliases} and {@code referencePaths}, one entry per row in the client-side editor;
 * the controller zips them into {@code (alias, referencePath)} pairs, skipping blank rows.
 */
public class MappingForm {

  private String sourceEntityType;
  private String targetEntityType;
  private String mappingValues;
  private List<String> aliases = new ArrayList<>();
  private List<String> referencePaths = new ArrayList<>();

  public String getSourceEntityType() {
    return sourceEntityType;
  }

  public void setSourceEntityType(String sourceEntityType) {
    this.sourceEntityType = sourceEntityType;
  }

  public String getTargetEntityType() {
    return targetEntityType;
  }

  public void setTargetEntityType(String targetEntityType) {
    this.targetEntityType = targetEntityType;
  }

  public String getMappingValues() {
    return mappingValues;
  }

  public void setMappingValues(String mappingValues) {
    this.mappingValues = mappingValues;
  }

  public List<String> getAliases() {
    return aliases;
  }

  public void setAliases(List<String> aliases) {
    this.aliases = aliases;
  }

  public List<String> getReferencePaths() {
    return referencePaths;
  }

  public void setReferencePaths(List<String> referencePaths) {
    this.referencePaths = referencePaths;
  }
}
