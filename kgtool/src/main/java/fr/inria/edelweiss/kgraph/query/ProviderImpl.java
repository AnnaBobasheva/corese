package fr.inria.edelweiss.kgraph.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.HashMap;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.xml.sax.SAXException;

import fr.inria.acacia.corese.triple.parser.ASTQuery;
import fr.inria.edelweiss.kgenv.parser.Pragma;
import fr.inria.edelweiss.kgenv.result.XMLResult;
import fr.inria.edelweiss.kgram.api.core.Node;
import fr.inria.edelweiss.kgram.api.query.Environment;
import fr.inria.edelweiss.kgram.api.query.Provider;
import fr.inria.edelweiss.kgram.core.Exp;
import fr.inria.edelweiss.kgram.core.Mapping;
import fr.inria.edelweiss.kgram.core.Mappings;
import fr.inria.edelweiss.kgram.core.Query;
import fr.inria.edelweiss.kgraph.core.Graph;
import fr.inria.edelweiss.kgtool.load.SPARQLResult;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

//import fr.inria.wimmics.sparql.soap.client.SparqlResult;
//import fr.inria.wimmics.sparql.soap.client.SparqlSoapClient;
/**
 * Implements service expression There may be local QueryProcess for some URI
 * (use case: W3C test case) Send query to sparql endpoint using HTTP POST query
 * There may be a default QueryProcess
 *
 * TODO: check use same ProducerImpl to generate Nodes ?
 *
 * @author Olivier Corby, Edelweiss INRIA 2011
 *
 */
public class ProviderImpl implements Provider {

    private static final String SERVICE_ERROR = "Service error: ";
    private static Logger logger = LogManager.getLogger(ProviderImpl.class);
    static final String LOCALHOST = "http://localhost:8080/sparql";
    static final String LOCALHOST2 = "http://localhost:8090/sparql";
    static final String DBPEDIA = "http://fr.dbpedia.org/sparql";
    HashMap<String, QueryProcess> table;
    Hashtable<String, Double> version;
    QueryProcess defaut;
    private int limit = 30;

    private ProviderImpl() {
        table = new HashMap<String, QueryProcess>();
        version = new Hashtable<String, Double>();
    }

    public static ProviderImpl create() {
        ProviderImpl p = new ProviderImpl();
        p.set(LOCALHOST, 1.1);
        p.set(LOCALHOST2, 1.1);
        //p.set(DBPEDIA, 1.1);
        return p;
    }

    @Override
    public void set(String uri, double version) {
        this.version.put(uri, version);
    }

    // everybody is 1.0 except localhost
    @Override
    public boolean isSparql0(Node serv) {
        if (serv.getLabel().startsWith(LOCALHOST)) {
            return false;
        }
        Double f = version.get(serv.getLabel());
        return (f == null || f == 1.0);
    }

    /**
     * Define a QueryProcess for this URI
     */
    public void add(String uri, Graph g) {
        QueryProcess exec = QueryProcess.create(g);
        exec.set(this);
        table.put(uri, exec);
    }

    /**
     * Define a default QueryProcess
     */
    public void add(Graph g) {
        QueryProcess exec = QueryProcess.create(g);
        exec.set(this);
        defaut = exec;
    }

    /**
     * If there is a QueryProcess for this URI, use it Otherwise send query to
     * spaql endpoint If endpoint fails, use default QueryProcess if it exists
     */
    @Override
    public Mappings service(Node serv, Exp exp, Environment env) {
        return service(serv, exp, null, env);
    }

    @Override
    public Mappings service(Node serv, Exp exp, Mappings lmap, Environment env) {
        Exp body = exp.rest();
        Query q = body.getQuery();

        QueryProcess exec = table.get(serv.getLabel());

        if (exec == null) {

            Mappings map = globalSend(serv, q, exp, lmap, env);
            if (map != null) {
                return map;
            }

            if (defaut == null) {
                map = Mappings.create(q);
                if (q.isSilent()) {
                    map.add(Mapping.create());
                }
                return map;
            } else {
                exec = defaut;
            }
        }

        ASTQuery ast = exec.getAST(q);
        Mappings map = exec.query(ast);

        return map;
    }

    /**
     * Cut into pieces when to many Mappings
     */
    Mappings globalSend(Node serv, Query q, Exp exp, Mappings map, Environment env) {
        CompileService compiler = new CompileService(this);

        // share prefix
        compiler.prepare(q);

        int slice = compiler.slice(q);

        ASTQuery ast = (ASTQuery) q.getAST();
        boolean hasValues = ast.getValues() != null;
        Mappings res = null;

        if (map == null || slice == 0 || hasValues) {
            // if query has its own values {}, do not slice
            return basicSend(compiler, serv, q, exp, map, env, 0, 0);
        }         
        else if (map.size() > slice) {           
            res = sliceSend(compiler, serv, q, exp, map, env, slice);            
        } else {
            res = basicSend(compiler, serv, q, exp, map, env, 0, map.size());
        }

        if (!hasValues) {
            ast.setValues(null);
        }

        return res;
    }
    
    /**
     * Execute service with Mappings map
     * Split Mappings into buckets with size = slice
     * Iterate service on each bucket
     */
    Mappings sliceSend(CompileService compiler, Node serv, Query q, Exp exp, Mappings map, Environment env, int slice) {

        List<Node> list = new ArrayList<Node>();
        if (exp.getNodeSet() == null) {
            list.add(serv);
        } else {
            list = exp.getNodeSet();
        }
        
        Mappings res = null;
        
        for (Node s : list) {
            if (env.getQuery().isDebug()){
                logger.debug("Service: " + s);
            }
            int size = 0;
            while (size < map.size()) {
                Mappings mm = send(compiler, s, q, map, env, size, size + slice);
                if (res == null) {
                    res = mm;
                } else if (mm != null) {
                    res.add(mm);
                }
                size += slice;
            }
        }
        
        if (res != null && list.size() > 1){  
            // Eliminate duplicates when several service URI (federated query) 
            res = res.distinct();
        }

        return res;
    }

    Mappings basicSend(CompileService compiler, Node serv, Query q, Exp exp, Mappings map, Environment env, int min, int max) {
        if (exp.getNodeSet() == null) {
            return send(compiler, serv, q, map, env, min, max);
        } else {
            Mappings res = null;
            for (Node s : exp.getNodeSet()) {
                if (env.getQuery().isDebug()){
                    logger.debug("Service: " + s);
                }
                Mappings mm = send(compiler, s, q, map, env, min, max);
                if (res == null) {
                    res = mm;
                } else if (mm != null) {
                    res.add(mm);
                }
            }
            if (res != null){            
                // Eliminate duplicates when several service URI (federated query) 
                res = res.distinct();
            }
            return res;
        }
    }

    /**
     * Send query to sparql endpoint using a POST HTTP query
     */
    Mappings send(CompileService compiler, Node serv, Query q, Mappings map, Environment env, int start, int limit) {
        Query g = q.getOuterQuery();
        int timeout = 0;
        Integer time = (Integer) g.getPragma(Pragma.TIMEOUT);
        if (time != null) {
            timeout = time;
        }
        try {

            // generate bindings from env if any
            compiler.compile(serv, q, map, env, start, limit);

            ASTQuery ast = (ASTQuery) q.getAST();

            String query = ast.toString();

            if (g.isDebug()) {
                logger.info("** Provider query: \n" + query);
            }

            //logger.info("** Provider: \n" + query);

            InputStream stream = doPost(serv.getLabel(), query, timeout);

            if (g.isDebug()) {
                //logger.info("** Provider result: \n" + sb);
            }

            Mappings res = parse(stream);

            if (g.isDebug()) {
                logger.info("** Provider result: \n" + res.size());
                if (g.isDetail()) {
                    logger.info("** Provider result: \n" + res);
                }
            }
            return res;
        } catch (IOException e) {
            logger.error(e);
            logger.error(q.getAST());
            g.addError(SERVICE_ERROR, e);
        } catch (ParserConfigurationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SAXException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if (g.isDebug()) {
            logger.info("** Provider error");
        }
        return null;
    }

    /**
     * ********************************************************************
     *
     * SPARQL Protocol client
     *
     * @throws IOException
     * @throws SAXException
     * @throws ParserConfigurationException
     *
     */
    Mappings parse(StringBuffer sb) throws ParserConfigurationException, SAXException, IOException {
        ProducerImpl p = ProducerImpl.create(Graph.create());
        XMLResult r = XMLResult.create(p);
        Mappings map = r.parseString(sb.toString());
        return map;
    }

    Mappings parse(InputStream stream) throws ParserConfigurationException, SAXException, IOException {
        ProducerImpl p = ProducerImpl.create(Graph.create());
        XMLResult r = SPARQLResult.create(p);
        Mappings map = r.parse(stream);
        return map;
    }

    public StringBuffer doPost2(String server, String query) throws IOException {
        URLConnection cc = post(server, query, 0);
        return getBuffer(cc.getInputStream());
    }

    public InputStream doPost(String server, String query, int timeout) throws IOException {
        URLConnection cc = post(server, query, timeout);
        return cc.getInputStream();
    }

    URLConnection post(String server, String query, int timeout) throws IOException {
        String qstr = "query=" + URLEncoder.encode(query, "UTF-8");

        URL queryURL = new URL(server);
        HttpURLConnection urlConn = (HttpURLConnection) queryURL.openConnection();
        urlConn.setRequestMethod("POST");
        urlConn.setDoOutput(true);
        urlConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        //urlConn.setRequestProperty("Accept", "application/rdf+xml,  application/sparql-results+xml");
        urlConn.setRequestProperty("Accept", "application/sparql-results+xml, application/rdf+xml");
        urlConn.setRequestProperty("Content-Length", String.valueOf(qstr.length()));
        urlConn.setRequestProperty("Accept-Charset", "UTF-8");
        urlConn.setReadTimeout(timeout);

        OutputStreamWriter out = new OutputStreamWriter(urlConn.getOutputStream());
        out.write(qstr);
        out.flush();

        return urlConn;

    }

    StringBuffer getBuffer(InputStream stream) throws IOException {
        InputStreamReader r = new InputStreamReader(stream, "UTF-8");
        BufferedReader br = new BufferedReader(r);
        StringBuffer sb = new StringBuffer();
        String str = null;

        while ((str = br.readLine()) != null) {
            sb.append(str);
            sb.append("\n");
        }

        return sb;
    }
//	public String callSoapEndPoint() {
//		SparqlSoapClient client = new SparqlSoapClient();
//		SparqlResult result = client.sparqlQuery("http://dbpedia.inria.fr/sparql", "select ?x ?r ?y where { ?x ?r ?y} limit 100");
//		String stringResult = result.toString();
//		return stringResult;
//	}
//
//	public static void main(String[] args) {
//		ProviderImpl impl = new ProviderImpl();
//		System.out.println(impl.callSoapEndPoint());
//	}
}
