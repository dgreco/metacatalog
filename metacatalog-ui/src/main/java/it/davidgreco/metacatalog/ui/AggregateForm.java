package it.davidgreco.metacatalog.ui;

/**
 * Form-backing bean for the aggregate authoring form.
 *
 * <p>The {@code rootType} is the aggregate root entity type whose combined schema drives the page,
 * and {@code document} is the raw JSON aggregate tree built by the client-side editor (see {@code
 * aggregate-form.js}). The controller converts that tree to YAML before handing it to the aggregate
 * creation endpoint.
 */
public class AggregateForm {

  private String rootType;
  private String document;

  /**
   * @return the name of the aggregate root entity type
   */
  public String getRootType() {
    return rootType;
  }

  public void setRootType(String rootType) {
    this.rootType = rootType;
  }

  /**
   * @return a JSON string containing the aggregate tree
   */
  public String getDocument() {
    return document;
  }

  public void setDocument(String document) {
    this.document = document;
  }
}
