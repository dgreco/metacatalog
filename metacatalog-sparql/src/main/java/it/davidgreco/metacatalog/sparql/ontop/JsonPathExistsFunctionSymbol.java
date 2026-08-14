package it.davidgreco.metacatalog.sparql.ontop;

import com.google.common.collect.ImmutableList;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.db.impl.DBBooleanFunctionSymbolWithSerializerImpl;
import it.unibz.inf.ontop.model.term.functionsymbol.impl.StringBooleanBinarySPARQLFunctionSymbolImpl;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.model.type.RDFDatatype;

/**
 * The SPARQL extension function {@code mtfn:jsonPathExists(?jsonText, ?jsonPathText) ->
 * xsd:boolean}, unfolded by Ontop into PostgreSQL's {@code jsonb_path_exists(CAST(x AS jsonb),
 * CAST(p AS jsonpath))} — a SQL/JSON path expression written in the SPARQL query and evaluated
 * entirely by the database.
 *
 * <p>Both arguments arrive as {@code xsd:string} lexical values, which Ontop's SPARQL machinery
 * ({@code ReduciblePositiveAritySPARQLFunctionSymbolImpl}) has already decomposed into DB-level
 * string terms by the time {@link #computeDBBooleanTerm} runs — so the DB symbol receives two
 * string expressions and the casts happen in SQL. The casts are hard-coded in the serializer
 * template rather than expressed as Ontop cast terms on purpose: a cast applied to a
 * <em>constant</em> argument is folded into a plain typed constant, which the generic serializer
 * then emits as a bare {@code 'literal'} — losing the explicit {@code jsonpath} cast.
 *
 * <p>{@link #canBePostProcessed} is {@code false}: this function exists precisely to be pushed
 * down, and Ontop has no Java-side evaluation for it.
 */
public class JsonPathExistsFunctionSymbol extends StringBooleanBinarySPARQLFunctionSymbolImpl {

  /**
   * The function IRI as written in SPARQL queries ({@code PREFIX mtfn: <http://metacatalog/fn#>}).
   */
  public static final String IRI_STRING = "http://metacatalog/fn#jsonPathExists";

  public JsonPathExistsFunctionSymbol(RDFDatatype xsdStringType, RDFDatatype xsdBooleanType) {
    // The symbol name must be globally unique: Ontop function symbols compare by name.
    super("MC_JSON_PATH_EXISTS", IRI_STRING, xsdStringType, xsdBooleanType);
  }

  @Override
  protected ImmutableTerm computeDBBooleanTerm(
      ImmutableList<ImmutableTerm> subLexicalTerms,
      ImmutableList<ImmutableTerm> typeTerms,
      TermFactory termFactory) {
    var dbTypeFactory = termFactory.getTypeFactory().getDBTypeFactory();
    return termFactory.getImmutableExpression(
        new JsonbPathExistsDBFunctionSymbol(
            dbTypeFactory.getDBStringType(), dbTypeFactory.getDBBooleanType()),
        subLexicalTerms.get(0),
        subLexicalTerms.get(1));
  }

  @Override
  public boolean canBePostProcessed(ImmutableList<? extends ImmutableTerm> arguments) {
    return false;
  }

  /**
   * The DB-level symbol with the fixed SQL template. Equality is by name, so per-call creation is
   * safe.
   */
  static final class JsonbPathExistsDBFunctionSymbol
      extends DBBooleanFunctionSymbolWithSerializerImpl {
    JsonbPathExistsDBFunctionSymbol(DBTermType dbStringType, DBTermType dbBooleanType) {
      super(
          "MC_JSONB_PATH_EXISTS",
          ImmutableList.of(dbStringType, dbStringType),
          dbBooleanType,
          false,
          (terms, termConverter, termFactory) ->
              String.format(
                  "jsonb_path_exists(CAST(%s AS jsonb), CAST(%s AS jsonpath))",
                  termConverter.apply(terms.get(0)), termConverter.apply(terms.get(1))));
    }
  }
}
