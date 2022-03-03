package fr.inria.corese.core.index;

import fr.inria.corese.kgram.api.core.Node;
import fr.inria.corese.core.Graph;
import static fr.inria.corese.core.index.EdgeManagerIndexer.IGRAPH;
import static fr.inria.corese.core.index.EdgeManagerIndexer.ILIST;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import fr.inria.corese.kgram.api.core.Edge;
import java.util.HashMap;

/**
 * Edge List of a predicate
 * Edge may be stored without predicate Node to spare memory
 * Edges are sorted according to 
 * 1- edge.getNode(index).getIndex() (subject)
 * 2- other nodes (object, graph)
 * index 0:
 * g1 s1 p o1 ; g2 s1 p o1 ; g1 s1 p o2 ; g2 s2 p o3 ; ... 
 * 
 * This version manages XSD datatypes this way:
 * integer, long, decimal (and short, byte, int, etc.) 
 * - have same node index when values are equal
 * - different labels are possible for same value: 1 and 01 are kept as is
 * with same node index
 * integer/long/decimal and double and float have different node index
 * Nodes with same node index which are not sameTerm are kept in the list:
 * s p 01, 1, 1.0, '1'^^xsd:long, 1e1
 *
 * @author Olivier Corby, Wimmics INRIA I3S, 2017
 *
 */
public class EdgeManager implements Iterable<Edge> {
    Graph graph;
    private EdgeManagerIndexer indexer;
    private Node predicate;
    private ArrayList<Edge> edgeList;
    private Comparator<Edge> comparatorIndex, comparator;
    private int index = 0;
    private int other = 0;
    private int next = IGRAPH;
    int meta = Edge.REF_INDEX;
    boolean indexedByNode = false;

    EdgeManager(EdgeManagerIndexer indexer, Node p, int i) {
        graph = indexer.getGraph();
        this.indexer = indexer;        
        predicate = p;
        edgeList = new ArrayList<>();
        index = i;
        if (index == 0) {
            other = 1;
        }
        else if (index == IGRAPH){
            next = 1;
        }
    }
    
    public Graph getGraph() {
        return graph;
    }

    ArrayList<Edge> getList() {
        return getEdgeList();
    }

    public int size() {
        return getEdgeList().size();
    }

    void clear() {
        getEdgeList().clear();
    }

    Edge get(int i) {
        return getEdgeList().get(i);
    }
    
    int getIndex(){
        return index;
    }

    /**
     * Remove duplicate edges
     * pragma: edge list is sorted
     */
    int reduce(NodeManager mgr) {
        ArrayList<Edge> l = new ArrayList<>();
        Edge pred = null;
        int count = 0, ind = 0;
        //System.out.println("before reduce: " + list);
        
        for (Edge edge : getEdgeList()) {
            if (pred == null) {
                l.add(edge);
                mgr.add(edge.getNode(getIndex()), getPredicate(), ind);
                ind++;
            } else if (equalExceptIfOneHasMetadata(pred, edge)) {
                // skip edge because it is redundant with pred
                count++;
            } else {
                l.add(edge);
                if (edge.getNode(getIndex()) != pred.getNode(getIndex())) {
                    mgr.add(edge.getNode(getIndex()), getPredicate(), ind);
                }
                ind++;
            }
            pred = edge;
        }
        
        setEdgeList(l);
        if (count > 0) {
            graph.setSize(graph.size() - count);
        }
        //System.out.println("after reduce: " + list);
        return count;
    }
    
    /**
     * return true when edges are equal, except if one has metadata
     */
    boolean equalExceptIfOneHasMetadata(Edge e1, Edge e2) {
        if (equalWithoutConsideringMetadata(e1, e2)) {
            // equal g s p o
            if (hasMetadata(e1, e2)) {
                getIndexer().setLoopMetadata(true);
                return false;
            }
            return true;
        }
        return false;
    }
    
    // when rdf star: equal g s p o without considering reference node t
    // g s p o = g s p o t = g s p o t2
    boolean equalWithoutConsideringMetadata(Edge e1, Edge e2) {
        return getComparatorEqualWithoutMetadata().compare(e1, e2) == 0;
    }
    
    boolean hasMetadata(Edge e1, Edge e2) {
        return graph.isEdgeMetadata() && (e1.getReferenceNode() != null || e2.getReferenceNode() != null);
    }
            
    /**
     * Context: RDF*
     * Merge duplicate triples, keep one metadata node
     * PRAGMA: index = 0, list is sorted and reduced
     * rdf* triple with same g s p o  and with or without ref id are not yet reduced
     * because they are considered equal by compare()
     * we remove these duplicate here and keep triple with ref id if any
     */
    void metadata() {
        new EdgeManagerMetadata(this).metadata();
    }
        
    
    /**
     * Replace subject/object by target node in map if any
     * map t1 -> t2 means replace t1 by t2
     * These nodes are triple ID metadata
     */
    void replace(HashMap<Node, Node> map) {
        boolean b = false;
        for (Edge e : getEdgeList()) {
            for (int i = 0; i < 2; i++) {
                if (e.getNode(i).isTriple()) {
                    Node n = map.get(e.getNode(i));
                    if (n != null) {
                        b = true;
                        e.setNode(i, n);
                    }
                }
            }
        }
        if (b) {
           complete();
        }
    }
    
    void complete() {
        sort();
        reduce(getIndexer().getNodeManager());
    }
    
    
    // compare subject object graph (not compare reference node if any)
    int compare3(Edge e1, Edge e2) {
        int res = compare2(e1, e2);        
        if (res == 0) {
            res = compareNodeTerm(e1.getNode(IGRAPH), e2.getNode(IGRAPH));
        }
        return res;
    }
    
    // compare subject object (not compare graph)
    int compare2(Edge e1, Edge e2) {
        for (int i = 0; i < 2; i++) {
            int res = compareNodeTerm(e1.getNode(i), e2.getNode(i));
            if (res != 0) {
                return res;
            }
        }
        return 0;
    }
    
    /**
     * generate map node -> index in edgeList
     */
    void indexNodeManager(NodeManager mgr) {
        Edge pred = null;
        int ind = 0;
        for (Edge ent : getEdgeList()) {
            if (pred == null) {
                mgr.add(ent.getNode(getIndex()), predicate, ind);
            } else if (ent.getNode(getIndex()) != pred.getNode(getIndex())) {
                mgr.add(ent.getNode(getIndex()), predicate, ind);
            }
            ind++;
            pred = ent;
        }
    }
    
    /**
     * Replace kg:rule Edge by compact EdgeInternalRule
     */
    
    void compact() {
        if (graph.isMetadata() || graph.isRDFStar()) {
            doCompactMetadata();
        }
        else {
            doCompact();
        }
    }

    // keep metadata, reset index
    void doCompactMetadata(){
        for (Edge ent : getEdgeList()) {
           ent.setIndex(-1);
        }
    }
    
    void doCompact(){
        ArrayList<Edge> l = new ArrayList<>(getEdgeList().size());
        for (Edge ent : getEdgeList()) {
           Edge ee = graph.getEdgeFactory().compact(ent);
           l.add(ee);
        }
        setEdgeList(l);
    }

    /**
     * Main function that sort Index edge list
     */ 
    void sort() {
        Collections.sort(getEdgeList(), getComparatorIndex());
    }

    /**
     * Copy Index(0) into this index
     */
    void copy(EdgeManager el) {
        getEdgeList().ensureCapacity(el.size());
        if (getIndex() < 2) {
            // we are sure that there are at least 2 nodes
            getEdgeList().addAll(el.getList());
        } else {
            for (Edge ent : el) {
                // if additional node is missing: do not index this edge
                if (ent.nbNode() > getIndex()) {
                    getEdgeList().add(ent);
                }
            }
        }
    }

    void add(Edge ent) {
        getEdgeList().add(ent);
    }

    void add(int i, Edge ent) {
        getEdgeList().add(i, ent);
    }

    Edge remove(int i) {
        Edge ent = getEdgeList().get(i);
        getEdgeList().remove(i);
        return ent;
    }

    /**
     * PRAGMA: All Edge in list have p as predicate, no duplicates Use case:
     * Rule Engine
     */
    void add(List<Edge> l) {
        getEdgeList().ensureCapacity(l.size() + getEdgeList().size());
        getEdgeList().addAll(l);
    }
    
   /**
     * Iterate edges with index node node1
     */
    Iterable<Edge> getEdges(Node node) {
        // node is bound, enumerate edges where node = edge.getNode(index)
        int n = findNodeIndex(node);
        if (n >= 0 && n < getEdgeList().size()) {
           return new EdgeManagerIterate(this, n);
        }
        return null;
    }
    
    Iterable<Edge> getEdges(Node node, int n) {
        return new EdgeManagerIterate(this, n);
    }
    
        
    // use case: rdfs entailment
    boolean exist(Edge edge) {
        int i = findEdgeEqualWithoutMetadata(edge);
        return i != -1;
    }


    /**
     * Return place where edge should be inserted in this Index return -1 if
     * already exists
     */
    int getPlace(Edge edge) {
        int i = find(edge);

        if (i >= getEdgeList().size()) {
            i = getEdgeList().size();
        } else if (getIndex() == 0) {
            //if (equalExceptIfMetadata(getEdgeList().get(i), edge)) {
            if (equalWithoutConsideringMetadata(getEdgeList().get(i), edge)) {
                if (hasMetadata(getEdgeList().get(i), edge)) {
                    // equal but have metadata: insert it
                    return i;
                } else {
                    // eliminate duplicate at load time for index 0                   
                    return -1;
                }
            }
        }

        return i;
    }
    


    /**
     * Place of edge in this Index, e.g. to insert edge
     */
    int find(Edge edge) {
        return basicFind(getComparatorIndex(), edge, 0, getEdgeList().size());
    }

    /**
     * use case: Construct find occurrence of edge for rdf star
     * @todo: Index is sorted and reduced ?
     */
    Edge findEdge(Edge edge) {
        int i = findEdgeEqualWithoutMetadata(edge);
        if (i == -1) {
            return null;
        }
        return getEdgeList().get(i);
    }

    /**
     * Find index of edge If not found, return -1
     */
    int findEdgeEqualWithoutMetadata(Edge edge) {
        int i = basicFind(getComparatorEqualWithoutMetadata(), edge, 0, getEdgeList().size());
        if (i >= size()) {
            return -1;
        }
        int res = getComparatorEqualWithoutMetadata().compare(edge, getEdgeList().get(i));
        if (res == 0) {
            return i;
        }

        return -1;
    }
    
    /**
     * There are two comparator
     * sort Index:     g s p o t < g s p o
     * retrieve Edge:  g s p o t = g s p o
     */
    int basicFind(Comparator<Edge> comp, Edge edge, int first, int last) {
        if (first >= last) {
            return first;
        } else {
            int mid = (first + last) / 2;
            int res = comp.compare(getEdgeList().get(mid), edge);
            if (res >= 0) {
                return basicFind(comp, edge, first, mid);
            } else {
                return basicFind(comp, edge, mid + 1, last);
            }
        }
    }
     
    /**
     * Test if an edge (n1 p n2) exist in this Index (in any named graph)
     * use case: rule engine
     */
    boolean exist(Node n1, Node n2) {
        int n = findNodeTerm(n1, n2, 0, getEdgeList().size());
        if (n >= 0 && n < getEdgeList().size()) {
            Edge ent = getEdgeList().get(n);
            if (n1.getIndex() == getNodeIndex(ent, 0)
                    && n2.getIndex() == getNodeIndex(ent, 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * return index of edge where
     * edge.getNode(index) == node1 and edge.getNode(other) == node2
     */
    
    int findNodeTerm(Node n1, Node n2, int first, int last) {
        if (first >= last) {
            return first;
        } else {
            int mid = (first + last) / 2;
            if (compare(getEdgeList().get(mid), n1, n2) >= 0) {
                return findNodeTerm(n1, n2, first, mid);
            } else {
                return findNodeTerm(n1, n2, mid + 1, last);
            }
        }
    }   

    
   /**
     * Find index of node as node(index) -1 if not found
     * use case: index of graph node n in graph node index
     */
    int findIndex(Node n) {
        int i = findNodeTerm(n, 0, getEdgeList().size());
        if (i >= 0 && i < getEdgeList().size()
                && getNodeIndex(i, getIndex()) == n.getIndex()) {
            return i;
        }
        return -1;
    }

    /**
     * return index of edge where edge.getNode(index) sameTerm node
     */
    int findNodeTerm(Node n, int first, int last) {
        if (first >= last) {
            return first;
        } else {
            int mid = (first + last) / 2;
            int res = compareNodeTerm(getNode(mid, getIndex()), n);
            if (res >= 0) {
                return findNodeTerm(n, first, mid);
            } else {
                return findNodeTerm(n, mid + 1, last);
            }
        }
    }
    
    // use case: getEdges(node)
    int findNodeIndex(Node node) {
        return getNodeIndex(node);
    }
    
    int getNodeIndex(Node n) {
        if (getIndexer().getNodeManager().isConsultable() 
                && ! n.getDatatypeValue().isNumber()
                && ! n.getDatatypeValue().isBoolean()){
            return  getIndexer().getNodeManager().getPosition(n, getPredicate());
        }
        return findNodeIndexBasic(n);
    }
    
    int findNodeIndexBasic(Node node) {
        int n = findNodeIndex(node, 0, getEdgeList().size());
        if (n >= 0 && n < getEdgeList().size()) {
            int i = getNodeIndex(n, getIndex());
            if (i == node.getIndex()) {
                return n;
            }
        }
        return -1;
    }

 
    int findNodeIndex(Node n, int first, int last) {
        if (first >= last) {
            return first;
        } else {
            int mid = (first + last) / 2;
            int res = compareNodeIndex(getNode(mid, getIndex()), n);
            if (res >= 0) {
                return findNodeIndex(n, first, mid);
            } else {
                return findNodeIndex(n, mid + 1, last);
            }
        }
    }
    
    
    int compare(Edge ent, Node n1, Node n2) {
        int res = compareNodeTerm(ent.getNode(getIndex()), n1);
        if (res == 0) {
            res = compareNodeTerm(ent.getNode(getOther()), n2);
        }
        return res;
    }

    /**
     * n1 n2 are node.getIndex()
     */
    int intCompare(int n1, int n2) {
        if (n1 < n2) {
            return -1;
        } else if (n1 == n2) {
            return 0;
        } else {
            return +1;
        }
    }
    
    /**
     * Compare nodes with sameTerm semantics
     * if value, datatype, label are equal : return 0
     * return +-1 otherwise
     */
    int compareNodeTerm(Node n1, Node n2){
        int res = intCompare(n1.getIndex(), n2.getIndex());
        if (res == 0){
            // same node index (compatible datatypes)
            // check datatype and label
            res = n1.getValue().compareTo(n2.getValue());
        }
        return res;
    }
    
    /**
     * compare named graph nodes
     * use case: find occurrence of delete edge
     * for rdf star edge with reference (see Construct find(edge))
     * named graph node of delete edge may be null
     * context: 
     * index == 0 (subject index)
     * edge subject and object are ==
     * if delete graph node is null, edge is found
     */
    int compareNodeTermNull(Node n1, Node n2) {
        if (n1 == null || n2 == null) {
            // occur only for find delete edge 
            // Construct getGraphManager().find(edge)
            return 0;
        }
        else {
            return compareNodeTerm(n1, n2);
        }
    }
    
    int compareNodeIndex(Node n1, Node n2){
        return intCompare(n1.getIndex(), n2.getIndex());       
    }
    
    @Override
    public Iterator<Edge> iterator() {
        return getEdgeList().iterator();
    }

    public Node getPredicate() {
        return predicate;
    }      

    // getNode(IGRAPH) must return getGraph()
    int getNodeIndex(Edge ent, int n) {
        return ent.getNode(n).getIndex();
    }

    // getNode(IGRAPH) must return getGraph()
    int getNodeIndex(int i, int n) {
        Edge ent = getEdgeList().get(i);
        return ent.getNode(n).getIndex();
    }
    
    // getNode(IGRAPH) must return getGraph()
    Node getNode(int i, int n) {
        Edge ent = getEdgeList().get(i);
        return ent.getNode(n);
    }
    

    /**
     * **************************************************************
     * There are two comparator
     * sort Index:     g s p o t < g s p o
     * retrieve Edge:  g s p o t = g s p o
     * after sort Index, reduce() + metadate() remove duplicates
     * and we keep only g s p o t (g s p o is removed if any)
     * At the end, the index contains only g s p o t (if any t)
     * Then, to retrieve an edge g s p o [t], we can consider edge equality 
     * without looking at metadata (because they are not both in the Index)
     */
    Comparator<Edge> createComparatorIndex(int n) {
        switch (n) {
            case ILIST:
                return createListComparator();
        }
        return createComparatorIndex();
    }
    
    Comparator<Edge> createComparator(int n) {
        switch (n) {
            case ILIST:
                return createListComparator();
        }
        return createComparator();
    }
    
    // called by EdegeManagerIndexer because every EdgeManager share same comparator object
    void setComparatorIndex(Comparator<Edge> c){
        comparatorIndex = c;
    }
    
    // used to sort edgeList and find edge place in index
    // g s p o t < g s p o
    Comparator<Edge> getComparatorIndex() {
        return comparatorIndex;
    }  
    
    // used to compare and retrieve edge when Index is sorted and reduced 
    // g s p o t = g s p o
    Comparator<Edge> getComparatorEqualWithoutMetadata() {
        return comparator;
    }

    void setComparator(Comparator<Edge> c){
        comparator = c;
    }
    
    /**
     * Compare two edges to sort and retrieve them in Index
     * rdf star edges are compared on g s p o only, not on ref id
     * hence they are considered equal when g s p o are equal 
     * not considering triple ref id
     * g s p o = g s p o t
     * @note:  
     * sort is not deterministic for rdf star triple with same g s p o 
     * list will be reduced by metadata()
     */
    
    // compare edges
    // g s p o t = g s p o
    Comparator<Edge> createComparator() {
        return createComparator(true);
    }
    
    // sort edgeList
    // true:  g s p o t = g s p o
    // false: g s p o t < g s p o
    Comparator<Edge> createComparatorIndex() {
        return createComparator(false);
    }
    
    
    //System.out.println("compare:\n"+e1 + " " + 
//        (e1.hasReferenceNode()?e1.getReferenceNode().getLabel():""));
//System.out.println(e2 + " " + 
//        (e2.hasReferenceNode()?e2.getReferenceNode().getLabel():""));
//                System.out.println("equalWithoutConsideringMetadata: " + equalWithoutConsideringMetadata);

    
    Comparator<Edge> createComparator(boolean equalWithoutConsideringMetadataNode) {

        return new Comparator<>() {
            boolean equalWithoutConsideringMetadata = equalWithoutConsideringMetadataNode;
     
            @Override
            public int compare(Edge e1, Edge e2) {
                
                // check the Index Node
                int res = compareNodeTerm(e1.getNode(getIndex()), e2.getNode(getIndex()));
                if (res != 0) {  
                    //System.out.println("subject: " + res);
                    return res;
                }
                
                res = compareNodeTerm(e1.getNode(getOther()), e2.getNode(getOther()));
                if (res != 0) {
                    //System.out.println("object: " + res);
                    return res;
                }
                                
                if (e1.nbNode() == 2 && e2.nbNode() == 2) {
                    // compare third Node (i.e. graph node in the general case)
                    res = compareNodeTermNull(e1.getNode(getNext()), e2.getNode(getNext()));
                    //System.out.println("graph: " + res);                    
                    return res;
                }
                
                // one of them has metadata, compare third Node
                if (getGraph().isMetadataNode()) {
                    // rdf star:  g s p o = g s p o t = g s p o t2
                    // compare third Node (i.e. graph in the general case)
                    res = compareNodeTermNull(e1.getNode(getNext()), e2.getNode(getNext()));
                    if (res == 0) {
                        // equal on g s p o
                        //System.out.println("equalWithoutConsideringMetadata: " + equalWithoutConsideringMetadata);
                        if (equalWithoutConsideringMetadata) {
                            //System.out.println("meta1: " + res);
                            return res;
                        }
                        else {
                            res = compareEqualWhenMetadata(e1, e2);
                            //System.out.println("meta2: " + res);
                            return res;
                        }
                    }
                    else {
                        //System.out.println("meta3: " + res);
                        return res;
                    }
                }

                // more than two nodes, not rdf star
                // common arity
                int min = Math.min(e1.nbNode(), e2.nbNode());

                for (int i = 0; i < min; i++) {
                    // check other common arity nodes
                    if (i != getIndex()) {
                        res = compareNodeTerm(e1.getNode(i), e2.getNode(i));
                        if (res != 0) {
                            return res;
                        }
                    }
                }

                if (e1.nbNode() == e2.nbNode()) {
                    // same arity, nodes are equal
                    // check graph node
                    return compareNodeTerm(e1.getNode(IGRAPH), e2.getNode(IGRAPH));
                }
                else if (e1.nbNode() < e2.nbNode()) {
                    // smaller arity edge is before
                    return -1;
                } else {
                    return 1;
                }

            }
        };
    }
     
     /**
      * Edges are equal on g s p o
      * edge with metadata < edge without metadata
      */ 
     int compareEqualWhenMetadata(Edge e1, Edge e2) {
         if (e1.hasReferenceNode()) {
             if (e2.hasReferenceNode()) {
                 return compareNodeTerm(e1.getReferenceNode(), e2.getReferenceNode());
             }
             else {
                 return -1;
             }
         }
         else if (e2.hasReferenceNode()) {
             return 1;
         }
         else {
             return 0;
         }
     }

    /**
     * sort in reverse order of edge timestamp
     * new edge first (for RuleEngine)
     */
    Comparator<Edge> createListComparator() {

        return new Comparator<>() {
            @Override
            public int compare(Edge o1, Edge o2) {
                int i1 = o1.getIndex();
                int i2 = o2.getIndex();
                
                if (i1 > i2) {
                    return -1;
                } else if (i1 == i2) {
                    return 0;
                } else {
                    return 1;
                }
            }
        };

    }

    public ArrayList<Edge> getEdgeList() {
        return edgeList;
    }

    public void setEdgeList(ArrayList<Edge> edgeList) {
        this.edgeList = edgeList;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getOther() {
        return other;
    }

    public void setOther(int other) {
        this.other = other;
    }

    public int getNext() {
        return next;
    }

    public void setNext(int next) {
        this.next = next;
    }

    public EdgeManagerIndexer getIndexer() {
        return indexer;
    }

    public void setIndexer(EdgeManagerIndexer indexer) {
        this.indexer = indexer;
    }

}
