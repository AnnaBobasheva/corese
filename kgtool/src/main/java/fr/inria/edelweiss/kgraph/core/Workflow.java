package fr.inria.edelweiss.kgraph.core;

import java.util.ArrayList;
import java.util.List;

import fr.inria.edelweiss.kgram.api.core.Edge;
import fr.inria.edelweiss.kgram.api.core.Node;
import fr.inria.edelweiss.kgraph.api.Engine;
import fr.inria.edelweiss.kgraph.logic.Entailment;

/**
 * 
 *  Manage a set of inference engines
 *  Loop until no inference is performed
 *  It is automatically called when isUpdate==true to perform inference and restore consistency
 *
 */
public class Workflow implements Engine {
	
	Graph graph;
	ArrayList<Engine> engines;
        // RDFS entailment
	private Entailment inference;
	boolean 
	isDebug = false,
	isIdle = true,
	isActivate = true,
	isClearEntailment = false;
	
	
	Workflow(Graph g){
		engines = new ArrayList<Engine>();
		graph = g;
	}
	
	void addEngine(Engine e){
		if (! engines.contains(e)){
			engines.add(e);
			e.init();
		}
	}
	
	public List<Engine> getEngines(){
		return engines;
	}
	
	public void removeEngine(Engine e){
		engines.remove(e);
	}
	
	public void setDebug(boolean b){
		isDebug = b;
	}
	
	public void setClearEntailment(boolean b){
		isClearEntailment = b;
	}
	
	/**
	 * When isUpdate==true
	 * manager.process() is called before executing a query
	 * to perform inference
	 */
	public synchronized boolean process(){
		boolean b = false;
		if (isActivate && isIdle){
			isIdle = false;
			b = run();
			isIdle = true;
		}
		return b;
	}
	
	public synchronized boolean process(Engine e){
		boolean b = false;
		if (isActivate && isIdle){
			isIdle = false;
			b = run(e);
			isIdle = true;
		}
		return b;
	}
	
	
	/**
	 * Run submitted engines until no inference is performed
	 */
	boolean run(){
		int count = 2;
		boolean isSuccess = false;
		
		if (isDebug && engines.size() > 0) {
			System.out.println("** W start: ");
		}

		while (count > 1){
			
			count = 0;
			
			for (Engine e : engines){

				if (e.isActivate()){

					if (isDebug) {
						System.out.println("** W run: " + e.getClass().getName());
						System.out.println("** W size before: " + graph.size);
					}

					boolean b = e.process();
					if (b){
						isSuccess = true;
						count++;
					}

					if (isDebug) {
						System.out.println("** W size after: " + graph.size);
					}
				}
			}
		}
		
		if (isDebug && engines.size() > 0) {
			System.out.println("** W end");
		}
		return isSuccess;
	}
	
	
	/**
	 * Run engine and submitted engines until no inference is performed
	 */	
	boolean run(Engine e){
		int size = 0;
		boolean isSuccess = false;
                int count = 2;
                
		while (count > 1){
                    count = 0;
			
			if (isDebug) {
				System.out.println("** W run: " + e.getClass().getName());
			}
			
			if (e.isActivate()){
				boolean b  = e.process();
                                if (b){
                                    isSuccess = true;
                                    count++;
                                }
			}
			
			boolean b = run();
                        if (b){
                            isSuccess = true;
                            count++;
                        }
		}
		
		return isSuccess;
	}

        @Override
	public void init() {
		for (Engine e : engines){
			e.init();
		}
	}

        @Override
	public void onDelete() {	
		if (isClearEntailment ){
			remove();		
		}
		
		for (Engine e : engines){
			e.onDelete();
		}
	}

        @Override
	public void onInsert(Node gNode, Edge edge) {
		for (Engine e : engines){
			e.onInsert(gNode, edge);
		}
	}

        @Override
	public void onClear() {
		for (Engine e : engines){
			e.onClear();
		}
	}
	
	/**
	 * Remove entailments
	 */
        @Override
	public void remove() {
		for (Engine e : engines){
			e.remove();
		}
	}


        @Override
	public void setActivate(boolean b) {
		isActivate  = b;
	}

        @Override
	public boolean isActivate() {
		return isActivate;
	}

        @Override
	public int type() {
		return WORKFLOW_ENGINE;
	}

	public void clear(){
		engines.clear();
	}
	
	public void removeEngine(int type) {
		for (int i = 0; i < engines.size(); ){
			if (engines.get(i).type() == type){
				engines.remove(engines.get(i));
			}
			else {
				i++;
			}
		}
	}
	
	public void setActivate(int type, boolean b) {

		for (Engine e : engines){
			if (e.type() == type){
				e.setActivate(b);
			}
		}
	}

    /**
     * @return the inference
     */
    public Entailment getEntailment() {
        return inference;
    }

    /**
     * @param inference the inference to set
     */
    public void setEntailment(Entailment inference) {
        this.inference = inference;
    }
    
    public void setEntailment() {
        Entailment i = Entailment.create(graph);
        setEntailment(i);
        addEngine(i);
    }
    
    public void setRDFSEntailment(boolean b) {
        pragmaRDFSentailment(b);
        if (! b) {
            getEntailment().remove();
        }
    }
    
    public void pragmaRDFSentailment(boolean b) {
        if (b) {
            if (getEntailment() == null) {
                setEntailment();
            } else {
                getEntailment().setActivate(true);
            }
        } else if (getEntailment() != null) {
            getEntailment().setActivate(false);
        }
    }

}
