package fr.inria.corese.cli.utils;

import java.nio.file.Path;

import fr.inria.corese.cli.utils.format.InputFormat;
import fr.inria.corese.cli.utils.format.OutputFormat;
import fr.inria.corese.core.Graph;
import fr.inria.corese.core.load.Load;
import fr.inria.corese.core.transform.Transformer;

public class GraphUtils {

    private GraphUtils() {
    }

    /**
     * Parse a file and load RDF data into a Corese Graph.
     *
     * @param inputFile   Path of the input RDF file.
     * @param inputFormat Input file serialization format.
     * @return Corese Graph with RDF data loaded.
     */
    public static Graph load(Path inputFile, InputFormat inputFormat) {
        Graph outputGraph = new Graph();

        Load ld = Load.create(outputGraph);
        try {
            ld.parse(inputFile.toString(), FromatManager.getCoreseinputFormat(inputFormat));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return outputGraph;
    }

    /**
     * Export RDF data from a Corese Graph into a serialized file format.
     * 
     * @param inputGraph   Graph with data to export.
     * @param outputFile   Path of the output RDF file.
     * @param outputFormat output file serialization format.
     */
    public static void export(Graph inputGraph, Path outputFile, OutputFormat outputFormat) {

        Transformer transformer = Transformer.create(inputGraph, FromatManager.getCoreseOutputFormat(outputFormat));
        try {
            transformer.write(outputFile.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}