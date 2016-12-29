package oops.evaluation;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oops.model.EvaluationResult;
import oops.model.Pitfall;
import oops.model.PitfallImportanceLevel;

import org.semanticweb.owlapi.rdf.rdfxml.renderer.RDFXMLRenderer;

/**
 * Author: Lukas Gedvilas<br>
 * Universidad Politécnica de Madrid<br><br>
 *
 * Evaluation service for the OOPS! plugin that uses the OOPS!(oops.linkeddata.es) web service
 * for the evaluation logic.
 */
public class OOPSEvaluator {
    
    private static final Logger logger = LoggerFactory.getLogger(OOPSEvaluator.class);
    
    private static final String OOPS_WS_ENDPOINT = "http://oops-ws.oeg-upm.net/rest";
    
    private static final String OOPS_WS_REQUEST_TEMPLATE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    		+ "<OOPSRequest>"
    		+ "    <OntologyURI></OntologyURI>"
    		+ "    <OntologyContent><![CDATA[ %s ]]></OntologyContent>"
    		+ "    <Pitfalls></Pitfalls>"
    		+ "    <OutputFormat></OutputFormat>"
    		+ "</OOPSRequest>";
    
    private static final int OOPS_WS_TIMEOUT = 30 * 1000; // set OOPS! WS timeout to 30s

    private static OWLOntology activeOntology;
    
    private static OOPSEvaluator instance = null;
    
    private static ArrayList<EvaluationListener> listeners = new ArrayList<EvaluationListener>();
    
    private static EvaluationResult evaluationResults = null;
    
    private static Runnable evaluationTask = () -> {
    	listeners.forEach(l -> l.onEvaluationStarted()); // notify all listeners about evaluation start
    	
    	Instant startInstant = Instant.now();
    	logger.info(String.format("evaluationTask[OOPSEvaluator] in thread %s", Thread.currentThread().getName()));
    	
		HashMap<String, ArrayList<Pitfall>> detectedPitfalls = new HashMap<String, ArrayList<Pitfall>>();
		
		activeOntology.getOWLOntologyManager();
		
		StringWriter rdfWriter = new StringWriter();
		RDFXMLRenderer rdfRenderer = new RDFXMLRenderer(activeOntology, rdfWriter);
		
		try {
			rdfRenderer.render();
			
			String rdfFormattedOntology = rdfWriter.toString();
			
			String oopsRequestBody = String.format(OOPS_WS_REQUEST_TEMPLATE, rdfFormattedOntology);
			
			String oopsResponse = sendOOPSRequest(oopsRequestBody);
			
			logger.info("The oopsResponse is -> " + oopsResponse);
			
			detectedPitfalls.put("http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza",
					new ArrayList<Pitfall>(
							Arrays.asList(new Pitfall(PitfallImportanceLevel.IMPORTANT, "P1", "P1 is about bla bla"),
									new Pitfall(PitfallImportanceLevel.CRITICAL, "P3", "P3 must be avoided!"),
									new Pitfall(PitfallImportanceLevel.CRITICAL, "P9", "P9 is unacceptable!"))));

			detectedPitfalls.put("http://www.co-ode.org/ontologies/pizza/pizza.owl#Spiciness",
					new ArrayList<Pitfall>(
							Arrays.asList(new Pitfall(PitfallImportanceLevel.MINOR, "P2", "Missing annotations..."),
									new Pitfall(PitfallImportanceLevel.IMPORTANT, "P8",
											"The ontology lacks information about equivalent properties "
													+ "(owl:equivalentProperty) in the cases of duplicated "
													+ "relationships and/or attributes."))));
	        
	        evaluationResults = new EvaluationResult(detectedPitfalls);
	        
	        logger.info(String.format("evaluationTask[OOPSEvaluator] finished in %d seconds", 
					Duration.between(startInstant, Instant.now()).getSeconds()));
	        
	        listeners.forEach(l -> l.onEvaluationDone(evaluationResults)); // send results to each listener
		} catch (Exception e) {
			logger.error(e.getLocalizedMessage());
			listeners.forEach(l -> l.OnEvaluationException(e));
		}
    };     
	
	/**
	 * Returns an OOPSEvaluator singleton instance
	 * 
	 * @return OOPSEvaluator singleton instance
	 */
	public static OOPSEvaluator getInstance() {
		if (instance == null) {
			instance = new OOPSEvaluator();
		}

		return instance;
	}
	
	private static String sendOOPSRequest(String oopsRequestBody) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(OOPS_WS_ENDPOINT).openConnection();
		connection.setRequestMethod("POST");
		connection.setReadTimeout(OOPS_WS_TIMEOUT);
		
		logger.info("Preparing for OOPS! WS post request...");
		
		// Send POST request
		connection.setDoOutput(true);
		DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
		wr.writeBytes(oopsRequestBody);
		wr.flush();
		wr.close();
		
		int responseCode = connection.getResponseCode();
		
		logger.info("The response code is -> " + responseCode);
		
		if (responseCode == 200) {
			BufferedReader in = new BufferedReader(
			        new InputStreamReader(connection.getInputStream()));
			String response = in.lines().collect(Collectors.joining("\n"));
			
			return response;
		} else {
			throw new Exception("The OOPS! web service request has failed with status code " + responseCode);
		}
	}

	/**
	 * Resets the evaluation results to prepare for a new evaluation
	 */
	public void resetEvaluationResults() {
		evaluationResults = null;
	}
	
	/**
	 * Add a listener for evaluation events
	 * 
	 * @param listener
	 *            the evaluation events listener to add
	 */
	public void addListener(EvaluationListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	/**
	 * Remove a listener for evaluation events
	 * 
	 * @param listener
	 *            the evaluation events listener to remove
	 */
	public void removeListener(EvaluationListener listener) {
		listeners.remove(listener);
	}

	/**
	 * This method is synchronized, only lets one thread to execute it and
	 * checks if the evaluation is already done. If there are results already
	 * available, it returns them directly. Otherwise, it evaluates the
	 * ontology, saves the results and returns them.
	 * 
	 * @param ontology
	 *            the ontology to evaluate
	 * @return the results after evaluating the given ontology
	 * @throws InterruptedException
	 */
	public void evaluate(OWLOntology ontology) throws InterruptedException {
		activeOntology = ontology;
		
		Thread thread = new Thread(evaluationTask);
		thread.start();
	}

}
