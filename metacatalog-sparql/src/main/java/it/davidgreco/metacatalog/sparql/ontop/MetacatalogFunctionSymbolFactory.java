package it.davidgreco.metacatalog.sparql.ontop;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.inject.Inject;
import it.unibz.inf.ontop.iq.tools.TypeConstantDictionary;
import it.unibz.inf.ontop.model.term.RDFTermTypeConstant;
import it.unibz.inf.ontop.model.term.functionsymbol.BooleanFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.FunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.FunctionSymbolFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.InequalityLabel;
import it.unibz.inf.ontop.model.term.functionsymbol.NotYetTypedEqualityFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.RDFTermFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.RDFTermTypeFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.SPARQLAggregationFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.SPARQLFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.db.DBFunctionSymbolFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.impl.FunctionSymbolFactoryImpl;
import it.unibz.inf.ontop.model.type.DBTermType;
import it.unibz.inf.ontop.model.type.RDFTermType;
import it.unibz.inf.ontop.model.type.TypeFactory;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.rdf.api.IRI;

/**
 * Ontop's {@code FunctionSymbolFactory} with the metacatalog SPARQL extension functions layered on
 * top. Activated by binding this class under the {@code
 * it.unibz.inf.ontop.model.term.functionsymbol.FunctionSymbolFactory} property in the embedded
 * Ontop configuration (see {@code OntopRepositoryConfig}) — Ontop's Guice modules resolve
 * implementation classes from settings, and a user-supplied binding wins over the defaults.
 *
 * <p>Delegation rather than subclassing: {@code FunctionSymbolFactoryImpl}'s only constructor is
 * private (Guice still instantiates it, and runs its {@code @Inject init()} to build the function
 * tables), so the built-in catalogue is reached through a delegate instance and only {@link
 * #getSPARQLFunctionSymbol} consults the extension table first. Having two factory instances in the
 * container is harmless — Ontop function symbols compare by name.
 */
public class MetacatalogFunctionSymbolFactory implements FunctionSymbolFactory {

  private final FunctionSymbolFactory delegate;
  private final ImmutableTable<String, Integer, SPARQLFunctionSymbol> extensionFunctionTable;

  @Inject
  protected MetacatalogFunctionSymbolFactory(
      FunctionSymbolFactoryImpl delegate, TypeFactory typeFactory) {
    this.delegate = delegate;
    this.extensionFunctionTable =
        ImmutableTable.<String, Integer, SPARQLFunctionSymbol>builder()
            .put(
                JsonPathExistsFunctionSymbol.IRI_STRING,
                2,
                new JsonPathExistsFunctionSymbol(
                    typeFactory.getXsdStringDatatype(), typeFactory.getXsdBooleanDatatype()))
            .build();
  }

  @Override
  public Optional<SPARQLFunctionSymbol> getSPARQLFunctionSymbol(String officialName, int arity) {
    var extension = extensionFunctionTable.get(officialName, arity);
    return extension != null
        ? Optional.of(extension)
        : delegate.getSPARQLFunctionSymbol(officialName, arity);
  }

  // Everything below is pure delegation.

  @Override
  public RDFTermFunctionSymbol getRDFTermFunctionSymbol() {
    return delegate.getRDFTermFunctionSymbol();
  }

  @Override
  public DBFunctionSymbolFactory getDBFunctionSymbolFactory() {
    return delegate.getDBFunctionSymbolFactory();
  }

  @Override
  public BooleanFunctionSymbol getIsARDFTermTypeFunctionSymbol(RDFTermType rdfTermType) {
    return delegate.getIsARDFTermTypeFunctionSymbol(rdfTermType);
  }

  @Override
  public BooleanFunctionSymbol getAreCompatibleRDFStringFunctionSymbol() {
    return delegate.getAreCompatibleRDFStringFunctionSymbol();
  }

  @Override
  public BooleanFunctionSymbol getLexicalNonStrictEqualityFunctionSymbol() {
    return delegate.getLexicalNonStrictEqualityFunctionSymbol();
  }

  @Override
  public NotYetTypedEqualityFunctionSymbol getNotYetTypedEquality() {
    return delegate.getNotYetTypedEquality();
  }

  @Override
  public BooleanFunctionSymbol getLexicalInequalityFunctionSymbol(InequalityLabel inequalityLabel) {
    return delegate.getLexicalInequalityFunctionSymbol(inequalityLabel);
  }

  @Override
  public BooleanFunctionSymbol getLexicalEBVFunctionSymbol() {
    return delegate.getLexicalEBVFunctionSymbol();
  }

  @Override
  public BooleanFunctionSymbol getRDF2DBBooleanFunctionSymbol() {
    return delegate.getRDF2DBBooleanFunctionSymbol();
  }

  @Override
  public RDFTermTypeFunctionSymbol getRDFTermTypeFunctionSymbol(
      TypeConstantDictionary dictionary,
      ImmutableSet<RDFTermTypeConstant> possibleConstants,
      boolean isSimplifiable) {
    return delegate.getRDFTermTypeFunctionSymbol(dictionary, possibleConstants, isSimplifiable);
  }

  @Override
  public Optional<SPARQLFunctionSymbol> getSPARQLDistinctAggregateFunctionSymbol(
      String officialName, int arity) {
    return delegate.getSPARQLDistinctAggregateFunctionSymbol(officialName, arity);
  }

  @Override
  public SPARQLAggregationFunctionSymbol getSPARQLGroupConcatFunctionSymbol(
      String separator, boolean isDistinct) {
    return delegate.getSPARQLGroupConcatFunctionSymbol(separator, isDistinct);
  }

  @Override
  public SPARQLFunctionSymbol getIRIFunctionSymbol(IRI baseIRI) {
    return delegate.getIRIFunctionSymbol(baseIRI);
  }

  @Override
  public SPARQLFunctionSymbol getIRIFunctionSymbol() {
    return delegate.getIRIFunctionSymbol();
  }

  @Override
  public SPARQLFunctionSymbol getBNodeTolerantSPARQLStrFunctionSymbol() {
    return delegate.getBNodeTolerantSPARQLStrFunctionSymbol();
  }

  @Override
  public FunctionSymbol getSPARQLEffectiveBooleanValueFunctionSymbol() {
    return delegate.getSPARQLEffectiveBooleanValueFunctionSymbol();
  }

  @Override
  public FunctionSymbol getCommonDenominatorFunctionSymbol(int arity) {
    return delegate.getCommonDenominatorFunctionSymbol(arity);
  }

  @Override
  public FunctionSymbol getCommonPropagatedOrSubstitutedNumericTypeFunctionSymbol() {
    return delegate.getCommonPropagatedOrSubstitutedNumericTypeFunctionSymbol();
  }

  @Override
  public FunctionSymbol getLangTagFunctionSymbol() {
    return delegate.getLangTagFunctionSymbol();
  }

  @Override
  public FunctionSymbol getRDFDatatypeStringFunctionSymbol() {
    return delegate.getRDFDatatypeStringFunctionSymbol();
  }

  @Override
  public BooleanFunctionSymbol getLexicalLangMatches() {
    return delegate.getLexicalLangMatches();
  }

  @Override
  public FunctionSymbol getUnaryLatelyTypedFunctionSymbol(
      Function<DBTermType, Optional<DBFunctionSymbol>> dbFunctionSymbolFct, DBTermType targetType) {
    return delegate.getUnaryLatelyTypedFunctionSymbol(dbFunctionSymbolFct, targetType);
  }

  @Override
  public FunctionSymbol getUnaryLexicalFunctionSymbol(
      Function<DBTermType, Optional<DBFunctionSymbol>> dbFunctionSymbolFct) {
    return delegate.getUnaryLexicalFunctionSymbol(dbFunctionSymbolFct);
  }

  @Override
  public FunctionSymbol getBinaryLatelyTypedFunctionSymbol(
      Function<DBTermType, Optional<DBFunctionSymbol>> dbFunctionSymbolFct, DBTermType targetType) {
    return delegate.getBinaryLatelyTypedFunctionSymbol(dbFunctionSymbolFct, targetType);
  }

  @Override
  public FunctionSymbol getExtractLexicalTermFromRDFTerm() {
    return delegate.getExtractLexicalTermFromRDFTerm();
  }

  @Override
  public FunctionSymbol getIdentity() {
    return delegate.getIdentity();
  }

  @Override
  public FunctionSymbol getQueryId() {
    return delegate.getQueryId();
  }
}
