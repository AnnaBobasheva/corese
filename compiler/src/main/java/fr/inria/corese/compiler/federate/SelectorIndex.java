package fr.inria.corese.compiler.federate;

import fr.inria.corese.compiler.eval.QuerySolver;
import fr.inria.corese.compiler.federate.util.ResourceReader;
import fr.inria.corese.kgram.core.Query;
import fr.inria.corese.sparql.exceptions.EngineException;
import fr.inria.corese.sparql.triple.parser.ASTQuery;
import fr.inria.corese.sparql.triple.parser.BasicGraphPattern;
import fr.inria.corese.sparql.triple.parser.Constant;
import fr.inria.corese.sparql.triple.parser.NSManager;
import fr.inria.corese.sparql.triple.parser.Triple;
import fr.inria.corese.sparql.triple.parser.Variable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Given federate query, create query to graph index to retrieve candidate endpoint uri list
 * who know federate query predicates
 * The uri list will be the input of standard source selection
 * Graph index plays the role of a search engine that discover relevant candidate endpoints
 * Graph index is an endpoint identified by a service clause in a predefined query pattern
 * Current graph index is: http://prod-dekalog.inria.fr/sparql
 * There is a default graph index query pattern: DEFAULT_QUERY_PATTERN
 * There is a Property FEDERATE_INDEX_PATTERN whose value is the path of a query pattern
 * By convention the query pattern MUST return distinct values of variable Selector.SERVER_VAR 
 * 
 */
public class SelectorIndex {
    public static Logger logger = LoggerFactory.getLogger(SelectorIndex.class);
    public static boolean SELECT_ENDPOINT = false;
    public static double NBSUCCESS = 0.5;
    public static String INDEX_URL = "http://prod-dekalog.inria.fr/sparql";
    
    // %1$s = predicate uri
    // %2$s = i in ?b_i
    public static final String OPTIONAL1 = 
        "optional {?s void:propertyPartition/void:property <%s> "
      + "bind (%s as ?b_%s)}\n";
    
    // %s = exp1+exp2 in bind(exp1+exp2 as ?c) 
    public static final String BIND     = "bind (%s as ?c)";
    
    // %s = i in expi = coalesce(?b_i, 0)
    public static final String EXP      = "coalesce(?b_%s, 0)";
    
    // %s = n in filter (?c >= n) where n = number of bound ?bi = number of present predicates in endpoint
    public static final String FILTER   = "filter (?c >= %s)";
                   
    // draft test for property partition
    public static final String OPTIONAL2 = 
        "optional {?s void:classPartition[void:propertyPartition[void:property <%s>]]." +
        "bind (1 as ?b_%s) }\n";
    
    private static final String FILTER_EXISTS = 
    "filter exists {?s void:propertyPartition/void:property <%s>}\n";
    
    public static String OPTIONAL = OPTIONAL1;
    
    // default source discovery query pattern
    private static final String DEFAULT_QUERY_PATTERN = 
            "/query/indexpatternendpoint.rq";
    // path set by Property FEDERATE_INDEX_PATTERN
    public static String QUERY_PATTERN = null;
    
    static HashMap<String, String> url2predicatePattern;
    static HashMap<String, String> url2queryPattern;

        
    Selector selector;
    ASTQuery ast;
    String indexURL;
    List<Constant> uriList;
    int totalValue = 0;
    
    static {
        url2predicatePattern = new HashMap<>();
        url2queryPattern = new HashMap<>();
        init();
    }
    
    // define for each index url: 
    // the sparql query pattern to search predicate p
    static void init() {
        definePredicatePattern(INDEX_URL, OPTIONAL);
        defineQueryPattern(INDEX_URL, getDefaultPattern(QUERY_PATTERN, DEFAULT_QUERY_PATTERN));
    }
    
    public static void definePredicatePattern(String url, String pattern) {
        url2predicatePattern.put(url, pattern);
    }
    
    public static void defineQueryPattern(String url, String pattern) {
        url2queryPattern.put(url, pattern);
    }
    
    // ast: input federate query
    SelectorIndex(Selector s, ASTQuery ast) {
        selector = s;
        this.ast = ast;
    }
    
    SelectorIndex(Selector s, ASTQuery ast, String uriIndex) {
        this(s, ast);
        this.indexURL = uriIndex;
    }
    

                       
    // generate query for endpoint URL source discovery
    // with federate query ast as input 
    // ast evaluation return candidate list of endpoint uri
    // variable for endpoint url is Selector.SERVER_VAR
    ASTQuery process() {
        try {
            ASTQuery a = getGraphIndexQuery();
            return a;
        } catch (EngineException ex) {
            logger.error(ex.getMessage());
            return ASTQuery.create();
        }
    }
    


    // generate query for endpoint URL source discovery    
    // for ast federate query
    ASTQuery getGraphIndexQuery() throws EngineException {
        // get query pattern with %s
        String pattern = getQueryPattern(indexURL);
        // generate test part to find federate query predicates
        String test = predicateTest(ast);
        String queryString = String.format(pattern, test);
        //System.out.println("pattern:\n"+pattern);
        //System.out.println("test:\n"+test);
        logger.info("Index query:\n"+queryString);
        Query q = getQuerySolver().compile(queryString);
        return q.getAST();
    }
    
    QuerySolver getQuerySolver() {
        return selector.getQuerySolver();
    }
    
    // generate test part to find federate query predicates
    String predicateTest(ASTQuery ast) {   
        StringBuilder sb = new StringBuilder();
        int i = 0;
        
        for (Constant p : ast.getPredicateList()) {
            if (p.getLabel().equals(ASTQuery.getRootPropertyURI())) {
                // skip variable predicate 
            } 
            else {
                // generate test for predicate 
                // specific namespace have more value than rdf: rdfs:
                int value = getValue(p.getLongName());
                totalValue += value;
                sb.append(String.format(getPredicatePattern(indexURL), 
                        p.getLongName(), value, i++)); 
            }
        }        
        // count number of predicates present in endpoint url
        count(sb, i);               
        return sb.toString();
    }
    
    // @todo
    // specific domain namespace: value 3
    // skos: owl: foaf: value 2
    // rdf: rdfs:  value 1    
    int getValue(String uri) {
        if (NSManager.nsm().isSystemURI(uri)) {
            return 1;
        }
        return 2;
    }
    
    // count number of predicates present in endpoint url
    // endpoint succeed when: 
    // endpoint nb predicates >= federate query nb predicates * rate
    // e.g. it has 0.5 * federate query nb predicates
    // NBSUCCESS can be set by 
    // property FEDERATE_INDEX_SUCCESS 0.75
    // annotation @fedSuccess 0.75
    // default is 0.5
    void count(StringBuilder sb, int i) {                
        // bind (coalesce(?b_i, 0) + coalesce(?b_j, 0) as ?c)
        sb.append(String.format(BIND, sum(i)));
        sb.append("\n");
        // filter (?c >= n)
        sb.append(String.format(FILTER, totalValue * NBSUCCESS));
    }
    
    // return bind (coalesce(?b_1, 0) + ... coalesce(?b_n) as ?c)
    // compurte number of fed query predicates found in endpoint
    StringBuilder sum(int n) {
        StringBuilder b = new StringBuilder();

        for (int j = 0; j < n; j++) {
            if (j > 0) {
                b.append(" + ");
            }
            // exp = coalesce(?b_j, 0)
            b.append(String.format(EXP, j));
        }
        return b;
    }
        
       
//    ASTQuery process(Variable serv, ASTQuery aa) {
//        try {
//            BasicGraphPattern bgp = compile(serv, aa);
//            ASTQuery a = getQuery2(serv, bgp);
//            return a;
//        } catch (EngineException ex) {
//            selector.getVisitor().logger.error(ex.getMessage());
//            return aa;
//        }
//    }
    
    
//    ASTQuery getQuery2(Variable serv, BasicGraphPattern bgp) throws EngineException {
//        Values values = Values.create(serv, uriList);
//        String pattern = getPattern("/query/indexpattern.rq");
//        String str = String.format(pattern, (SELECT_ENDPOINT?"":values), bgp.toStringBasic());
//        System.out.println("pattern:\n"+str);
//        Query q = selector.getQuerySolver().compile(str);
//        return q.getAST();
//    }
    
    
    String generate2(ASTQuery ast) {   
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Constant p : ast.getPredicateList()) {
            if (p.getLabel().equals(ASTQuery.getRootPropertyURI())) {

            } 
            else {
                sb.append(String.format(FILTER_EXISTS,p.getLongName())); 
            }
        }
        
        return sb.toString();
    }
        
    BasicGraphPattern compile(Variable serv, ASTQuery aa) {
        BasicGraphPattern bgp = BasicGraphPattern.create();
        int i = 0;
        
        for (Constant p : ast.getPredicateList()) {
            if (p.getLabel().equals(ASTQuery.getRootPropertyURI())) {

            } else {
                // ?meta dcterms:modified ?modif .
                // ?meta kgi:curated ?s .
                // ?s void:sparqlEndpoint ?serv
                
                // bind (exists {?s void:propertyPartition/void:property $pred} as ?b)
                Constant part = aa.createQName("void:propertyPartition");
                Constant prop = aa.createQName("void:property");
                
                Variable s  = Variable.create("?s");
                Variable x  = Variable.create("?x");
                
                Triple t1 = aa.triple(s, part, x);
                Triple t2 = aa.triple(x, prop, Constant.create(p.getLongName()));
                
                // exists { ?serv idx:namespace/idx:data/idx:predicate predicate }
                Variable var = selector.exist(aa, bgp, aa.bgp(t1, t2), i);
                selector.declare(p, var);
                                
                i++;
            }                      
        }
        
                
        return bgp;
    }
    
    String getPredicatePattern(String url) {
        String str = url2predicatePattern.get(url);
        if (str == null) {
            return OPTIONAL;
        }
        return str;
    }
            
    String getQueryPattern(String url) {
        String str = url2queryPattern.get(url);
        if (str == null) {
            return getDefaultPattern(QUERY_PATTERN, DEFAULT_QUERY_PATTERN);
        }
        return str;
    }
    
    // path is given by Property FEDERATE_INDEX_PATTERN
    // defaut is default graph index query pattern
    static String getDefaultPattern(String path, String defaut) {
        String pattern = "select * where {%s}";
        try {
            if (path == null) {
                return new ResourceReader().getResource(defaut);
            } else {
                return new ResourceReader().readWE(path);
            }
        } catch (IOException ex) {
            logger.error(ex.getMessage());
        }
        return pattern;
    }
    
    
    
}
