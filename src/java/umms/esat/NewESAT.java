package umms.esat;

import umms.esat.Window;
import umms.esat.EventCounter;

import java.io.IOException;
import java.text.ParseException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.NoSuchElementException;

import org.apache.log4j.Logger;
import org.apache.log4j.LogManager;
import org.apache.log4j.BasicConfigurator;

import umms.core.annotation.Annotation.Strand;
import umms.core.annotation.BEDFileParser;
import umms.core.annotation.BasicAnnotation;
import umms.core.annotation.Gene;
import umms.core.exception.RuntimeIOException;
import broad.core.util.CLUtil;
import broad.core.util.CLUtil.ArgumentMap;
import broad.core.datastructures.IntervalTree;
import broad.core.datastructures.IntervalTree.Node;
import net.sf.samtools.*;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import umms.esat.SAMSequenceCountingDict;
import umms.esat.SAMSequenceCountingDictShort;
import umms.esat.SAMSequenceCountingDictFloat;
import umms.core.readers.MappingTableReader;

import umms.core.utils.NexteraPreprocess;

//import umms.core.utils.ESATUtils;

public class NewESAT {
	
	// This usage message needs to be updated to reflect the actual operation of NewESAT!!!
	static final String usage = "Usage: NewESAT -in <input Bam File> | -alignments <input filelist file>"+
			"\n\t-annotations <reference annotation file [BED file]> | -geneMapping <gene-to-transcript map file>"+
			"\n\t-out <output file basename>"+
			"\n\t**************************************************************"+
			"\n\t\tOPTIONAL arguments"+
			"\n\t**************************************************************"+
			"\n\t-quality <minimum alignment quality [default: no filtering]>"+ 
			"\n\t-task <score3p | score5p> [default: score3p]"+
			"\n\t-unstranded [default: stranded]"+
			"\n\tWindow parameters:"+
			"\n\t\t-wLen <window length [default: 400]>"+
			"\n\t\t-wOlap <window overlap [default: 0]"+
			"\n\t\t-wExt <extension past end of transcript [default: 400]>"+
			"\n\t\t-all [default: disabled]"+
			"\n\tSignificance testing:"+
			"\n\t\t-sigTest <minimum allowable p-value>\n"+
			"\n\tPre-processing alignments from Nextera library reads:"+
			"\n\t\t-nextPrep [default: off]"+
			"\n\t\t-uMin <minimum number of reads per UMI per transcript to be considered valid [default: 10]";
	
	private static HashMap<String,ArrayList<File>> bamFiles;     // key=experiment ID, File[]= list of input files for the experiment
	private static File outFile;
	private static String annotationFile;
	private static int windowLength;
	private static int windowOverlap;
	private static int windowExtend;
	private static boolean allWindows;  // save all significant windows (default, only single window position with the highest counts 
										// within a set of contiguous overlapping windows.)
	private static String multimap;     // one of "ignore", "normal" or "scale"
	private static boolean qFilter;
	private static int qThresh;         // quality threshold (reads must be GREATER THAN qThresh, if filtering is on 
	private static boolean gMapping;
	private static File gMapFile;       // name of the gene mapping file
	private static String task; 		// 3' or 5' library
	private static float pValThresh;		// minimum allowable p-value for window significance testing
	private static boolean stranded;    // allow for unstranded analysis (defaults to stranded)
	
	/* single-cell parameters */
	private static boolean nextPreprocess;    // Nextera library reads preprocessing flag
											  // NOTE: barcode and UMI are in the read name, separated by "_".
	private static int umiMin;			// minimum number of reads per UMI that must be mapped to a transcript to be considered a valid UMI 
	
	static final Logger logger = LogManager.getLogger(NewESAT.class.getName());

	private static HashMap<String,HashMap<String,TranscriptCountInfo>> countsMap;
	private static SAMSequenceCountingDict bamDict;
	private static Hashtable<String, Gene> geneTable;
	
	public NewESAT(String[] args) throws IOException, ParseException, IllegalArgumentException {
	
		/*
		 * @param for ArgumentMap - size, usage, default task
		 * argMap maps the command line arguments to the respective parameters
		 */
	
		// Configure the logger:
		BasicConfigurator.configure();
		
		/* seems like a useful utility that can probably be stripped down */
		ArgumentMap argMap = CLUtil.getParameters(args,usage,"score3p");  /* no default task for now */
		if (!validateArguments(argMap)) {
			logger.error("Please correct input arguments and retry.");
			throw new IOException();
		}

		/* Only create floating-point counters if multimap=="scale" */ 
		if (multimap.equals("scale")) {
			// multimapped read scaling is not implemented yet:
			bamDict = new SAMSequenceCountingDictFloat();	
		} else {
			bamDict = new SAMSequenceCountingDictShort();
		}

		/* START TIMING */
		long startTime = System.nanoTime();
		
		/* If collapsing transcripts down to the gene level, load the gene annotation mapping file */
		if (gMapping) {
			geneTable = loadGeneTableFromFile(gMapFile); 
		}

		/* Either use the existing gene-to-transcript mapping table, or load in a genomic annotation file */
		Map<String, Collection<Gene>> annotations;
		if (gMapping) {
			// Create the annotations map, keyed by chromosome:
			annotations = geneMapToAnnotations(geneTable);  
		} else {
			// load the annotations from the annotation (BED) file:
			annotations =  BEDFileParser.loadDataByChr(new File(annotationFile));	
		}
		
		/*****************************************************************************************************
		 * BEGIN Single-cell data preprocessing 
		 ******************************************************************************************************/
		if (nextPreprocess) {
			NexteraPreprocess nextData = new NexteraPreprocess(bamFiles, annotations, qFilter, qThresh, multimap, windowExtend, stranded, task, umiMin);
			bamFiles = nextData.getPreprocessedFiles();
		}	
		
		/*****************************************************************************************************
		 * END Single-cell data preprocessing 
		 ******************************************************************************************************/

		/* collect all read start location counts from the input alignments file(s) */
		bamDict = countReadStartsFromAlignments(bamDict, bamFiles, qFilter, qThresh, multimap, stranded); 
	
		/* Count all reads beginning within the exons of each of the transcripts in the annotationFile */
		countsMap = bamDict.countWindowedTranscriptReadStarts(annotations, windowLength, windowOverlap, windowExtend, task, pValThresh, allWindows);
		
		/* Make an intervalTree containing only Windows with non-zero counts across ALL experiments */
		HashMap<String, HashMap<String, IntervalTree<EventCounter>>> windowTree = makeCountingIntervalTree(countsMap, bamFiles.keySet().size());
		
		/* re-process the alignments files to count all reads that start within intervals in the windowTree (i.e., within windows in cleanCountsMap) */
		fillExperimentWindowCounter(windowTree, bamFiles, qFilter, qThresh, multimap, stranded);
		
		/* STOP AND REPORT TIMING */
		long stopTime = System.nanoTime();
		logger.info("Total processing time: "+(stopTime-startTime)/1e9+" sec\n");

		/* need a new version of a writer that writes counts in each window for each experiment !!!!!!! */
		//writeOutputESATFile(countsMap, annotations, outFile);   // ?????
		//writeSummaryCountsFile(windowTree, outFile);
		writeExperimentCountsFile(windowTree, bamFiles, outFile);
		
	}
									
	public static void main(String[] args) throws ParseException, IOException {
		new NewESAT(args);
	}
	

	private static boolean validateArguments(ArgumentMap argMap) throws IOException {
		/* Validates the input arguments to ensure that all parameters are consistent and
		 * fills in provided and default values for all required parameters.
		 * 
		 * In the case of single/multiple input alignment files, either the -in <inputFile> flag or
		 * -alignments <inputFileListFile> can be used. Multiple files (-alignments) overrides -in. 
		 * The bamFiles argument (a String array) will contain either a single file name (-in), or the
		 * full set of all alignment files (-alignments). NOTE: In the case of multiple alignments files
		 * ONLY the header of the first file is used to determine the alignment segments.ls
		 * 
		 * 
		 * @param	args	an ArgumentMap containing the input parameters from the command line.
		 * @return	success	a boolean flag indicating success/failure of the parameter set validity.
		 */
		
		/* Windowed read count test parameters */
		windowLength = argMap.isPresent("wLen")? argMap.getInteger("wLen") : 400;
		if (windowLength<1) {
			logger.error("Illegal value for wLen: "+windowLength+" (window length must be >= 1.");
			throw new IllegalArgumentException();
		}
		windowOverlap = argMap.isPresent("wOlap")? argMap.getInteger("wOlap") : 0;   // only allow overlap if window significance is being tested
		if (windowOverlap<0) {
			logger.error("Illegal value for wOlap: "+windowOverlap+" (window overlap must be >= 0.");
			throw new IllegalArgumentException();
		}
		windowExtend = argMap.isPresent("wExt")? argMap.getInteger("wExt") : 400;
		if (windowExtend<0) {
			logger.error("Illegal value for wExt: "+windowExtend+" (extension must be >= 0.");
			throw new IllegalArgumentException();
		}
		allWindows = argMap.isPresent("all")? true : false;
		
		/* Multimapping parameters */
		if (!argMap.isPresent("multimap")) {
			multimap = "normal";     // default to treat multimapped reads as normal reads
		} else {
			multimap = argMap.get("multimap");
			if (!(multimap.equals("ignore") || multimap.equals("normal") || multimap.equals("scale"))) {
				logger.error("-multimap flag must be one of ignore, normal, or scale (is set to "+multimap+")");
				throw new IOException();
			}
		}
		
		/* 3' or 5' library (defaults to 3') */
		task = argMap.getTask();  
		
		/* Quality filtering */
		if (!argMap.isPresent("quality")) {
			qFilter = false;
		} else {
			qFilter = true;
			qThresh = argMap.getInteger("quality");   // quality must be GREATER THAN qThresh for read to be processed
			if (qThresh<0) {
				logger.error("Illegal value for quality: "+qThresh+" (quality threshold must be >= 0.");
				throw new IllegalArgumentException();
			}
		}
		
		/* Stranded or unstranded alignments */
		stranded = argMap.isPresent("unstranded")? false : true;
		
		/* single-cell pre-processing? */
		nextPreprocess = argMap.isPresent("nextPrep") ? true : false;
		umiMin = argMap.isPresent("umiMin") ? argMap.getInteger("umiMin") : 10;
		
		// Allow multiple inputs 
		if (argMap.isPresent("alignments")){
			// The input file file contains a listing of all input files, with an experiment identifier for
			// each file as: <experimentID>\t<input BAM file>
			// fill the File array with the list of input files
			String inputFileFile = argMap.get("alignments");
			bamFiles = loadBamFileList(inputFileFile);    
		}
		if (argMap.hasInputFile()) {
			bamFiles = new HashMap<String, ArrayList<File>>();
			// Set the default experiment name to "Exp1"
			bamFiles.put("Exp1",new ArrayList<File>());
			bamFiles.get("Exp1").add(new File(argMap.getInput()));
		}
		
		// output file
		outFile = new File(argMap.getOutput());

		// Must have either an annotations file or a gene-to-transcript mapping file (gene-mapping takes precedence over annotation file):
		if (argMap.isPresent("geneMapping"))
		{
			//  allow all transcripts for a gene to be read from an input file.
			// The input file is assumed to be a table in the format provided by the UCSC website (genomes.ucsc.edu)
			// with the following features selected:
			// Clade: Mammal
			// genome: human
			// assembly: <appropriate assembly>
			// table: refGene
			// region: genome
			// output format: all fields from selected table
			//
			// The table MUST have the following columns:
			//   name: the transcript RefSeq ID
			//   chrom: chromosome
			//   strand: strand, + or -
			//   txStart, txEnd: transcript start and end location (0-based)
			//   exonStarts, exonEnds: starting and ending location of each exon (paired, 0-based)
			//   name2: gene symbol
			gMapping = true;
			gMapFile = new File(argMap.get("geneMapping"));   // name of the gene mapping file
		} else if (argMap.isPresent("annotations")) {
			annotationFile = argMap.get("annotations");
			if(!annotationFile.endsWith(".bed") && !annotationFile.endsWith(".BED")){
				logger.error("Please supply an annotation file in the BED format",new RuntimeIOException());
				return false;
			}
			gMapping = false;
		} else {
			logger.error("Either an annotation file or gene-to-transcript mapping file must be provided.");
			return false;
		}
		
		// Significance testing:
		pValThresh = argMap.isPresent("sigTest")? argMap.getFloat("sigTest") : 1;   

		return true;   // default return value if all tests pass
	}

//	/*******************************************************************************************************/
//	/****** Methods pulled out of ESATUtils.java ***********************************************************/
//	/*******************************************************************************************************/
	public static void writeOutputBEDFile(HashMap<String,HashMap<String,LinkedList<Window>>> countsMap, File outFile, boolean collapseGenes) throws IOException {
		// Open the output file:
		FileWriter writer = new FileWriter(outFile);

		/* write the data in bedGraph format */
		// Header line:
		writer.write("track type=bedGraph name=\"BedGraph Format\" description=\"BedGraph format\" visibility=full color=200,100,0 altColor=0,100,200 priority=20\n");
		for (String chr:countsMap.keySet()) {
			for (String gene:countsMap.get(chr).keySet()) {
				ListIterator<Window> wIter = countsMap.get(chr).get(gene).listIterator();
				while (wIter.hasNext()) {
					try {
						Window w = wIter.next();
						// bedGraph format: chr\tstart\tend\tvalue:
						String oStr = chr+"\t"+w.getStart()+"\t"+w.getEnd()+"\t"+w.getCount()+"\n";
						writer.write(oStr);
					} catch (NoSuchElementException e) {
						logger.error("NoSuchElementException for "+gene);
					}
				}	
			}
		}
		writer.flush();
		writer.close();
	}

	public void writeExperimentCountsFile(HashMap<String, HashMap<String, IntervalTree<EventCounter>>> windowTree,
			HashMap<String,ArrayList<File>> bamfiles, File outFile) throws IOException {
				
		String baseName = outFile.getAbsolutePath();
		File wFile = new File(baseName+".window.txt");  // window-level counts file
		File gFile = new File(baseName+".gene.txt");  // gene-level counts file
		
		// Open the output files:
		FileWriter wWriter = new FileWriter(wFile);
		FileWriter gWriter = new FileWriter(gFile);

		// Header line for window file:
		String wStr = "Symbol\tchr\tstart\tend\tstrand";
		// Header line for gene file:
		String gStr = "Symbol\tchr\tstrand";
		for (String e:bamfiles.keySet()) {
			wStr+="\t"+e;
			gStr+="\t"+e;
		}
		wWriter.write(wStr+"\n");   // write the window file header  
		gWriter.write(gStr+"\n");   // write the gene file header  
		
		int nExp = bamfiles.keySet().size();   // number of experiments
		
		for (String strand:windowTree.keySet()) {
			for (String chr:windowTree.get(strand).keySet()) {
				// Iterate through the interval tree to extract the counts for each window:
				Iterator<EventCounter> eIter = windowTree.get(strand).get(chr).valueIterator();
				while (eIter.hasNext()) {
					EventCounter e = eIter.next();
					// extract the gene name:
					String[] wLoc = e.getName().split("\t");
					String gName = wLoc[0]; 
					
					if (wLoc.length==1) {
						// if the event counter name only has one field, it is a gene-level counter:
						String oStr = gName+"\t"+chr+"\t"+strand;
						float counts=0;
						for (int i=0; i<nExp; i++) {
							oStr += "\t"+e.getCounts(i);
							counts+=e.getCounts(i);
						}
						if (counts>0) {
							// don't bother writing genes wiith no counts
							gWriter.write(oStr+"\n");	// write to gene-level file
						}
					} else {
						// otherwise, it is a window-level counter:
						String oStr = e.getName();
						oStr += "\t"+strand;
						for (int i=0; i<nExp; i++) {
							oStr+="\t"+e.getCounts(i);
						}
						wWriter.write(oStr+"\n"); 	// write to window-level file
					}
				}
			}
		}

		// flush and close the writers:
		wWriter.flush();
		wWriter.close();
		gWriter.flush();
		gWriter.close();
	}	
	
	public static void writeOutputESATFile(HashMap<String,HashMap<String,LinkedList<Window>>> countsMap, 
			Map<String, Collection<Gene>> annotations, 
			File outFile) throws IOException {

		// Open the output file:
		FileWriter writer = new FileWriter(outFile);

		/* write the data in ESAT format */
		// Header line:
		writer.write("Symbol\ttranscriptIDs\tcounts\n");

		for (String chr:annotations.keySet()) {
			Iterator<Gene> gIter = annotations.get(chr).iterator();
			while (gIter.hasNext()) {
				Gene thisGene = gIter.next();
				// Gene symbol:
				String symbol = thisGene.getName();
				// Isoforms:
				Collection<Gene> isoforms = thisGene.getIsoforms();
				Iterator<Gene> iIter = isoforms.iterator();
				String iStr = null;         // build the list of isoforms
				while (iIter.hasNext()){
					Gene iso = iIter.next();
					if (iStr==null) {
						iStr = iso.getName();
					} else {
						if (!iso.getName().equals(symbol)) {
							iStr += ","+iso.getName();
						}
					}
				}
				// Window counts:
				if (!(countsMap.containsKey(chr) && countsMap.get(chr).containsKey(symbol))) {
					logger.info("No alignments for "+symbol+" ("+chr+")");
					continue;
				}
				Iterator<Window> wIter = countsMap.get(chr).get(symbol).iterator();
				float totalCounts = 0;
				while (wIter.hasNext()) {
					Window w = wIter.next();
					totalCounts+=w.getCount();
				}
				// write the line to the output file:
				writer.write(symbol+"\t"+iStr+"\t"+totalCounts+"\n");
			}
		}
		writer.flush();
		writer.close();
	}
	
	public static HashMap<String, ArrayList<File>> loadBamFileList(String fileListFile) 
		throws IOException {
		HashMap<String, ArrayList<File>> expBamFiles = new HashMap<String, ArrayList<File>>();
		
		BufferedReader br = new BufferedReader(new FileReader(fileListFile));
		String s;
		while((s = br.readLine())!= null){
			String[] strSplit = s.split("\t");
			//Check for blank lines or comments (start with "#"):
			if (strSplit.length < 2 || strSplit[0].startsWith("#")) {
				continue;
			} 
			//Map of sample name to alignment File
			String exp = strSplit[0];
			File expFile = new File(strSplit[1]);
			if (!expBamFiles.containsKey(exp)) {
				expBamFiles.put(exp, new ArrayList<File>());
			}
			expBamFiles.get(exp).add(expFile);
		}		
		br.close();
		return expBamFiles;
	}

	private static List<Integer> stringToIntList(String[] vals) {
		List<Integer> outList = new ArrayList<Integer>();
		for (int i=0;i<vals.length;i++) {
			outList.add(Integer.parseInt(vals[i]));
		}
		return outList;
	}
	
	public static Map<String, Collection<Gene>> geneMapToAnnotations(Hashtable<String, Gene>gTable) {
		Map<String, Collection<Gene>> annotations = new TreeMap<String, Collection<Gene>>();
		for (String symbol:gTable.keySet()) {
			String chr = gTable.get(symbol).getChr();   // get the chromosome (used as annotation key)
			if (!annotations.containsKey(chr)) {
				annotations.put(chr, new TreeSet<Gene>());
			}
			//annotations.get(chr).add(gTable.get(symbol));
			Gene g = gTable.get(symbol);
			annotations.get(chr).add(g);
		}
		return annotations;
	}
	
	public static Hashtable<String, Gene> loadGeneTableFromFile(File gMapFile) throws IOException {
		
		final MappingTableReader mapFile;
		
		long startGmapTime = System.nanoTime();    // load timer
		// open the gene mapping file
		mapFile = new MappingTableReader(gMapFile);
		// make sure it has the mandatory columns:
		String[] reqFields = {"name","chrom","strand","txStart","txEnd","exonStarts","exonEnds","name2"};
		if (!mapFile.hasMandatoryFields(reqFields)) {
			logger.error("Input mapping file "+gMapFile+" is missing required columns.");
		}
		// build gene-to-transcript map
		Hashtable<String, Gene> geneTable = new Hashtable<String, Gene>();
		boolean notDone = true;
		// NOTE: "name" is the transcriptID, "name2" is the gene symbol
		String[] cOrder = {"name2","chrom","txStart","txEnd","name","strand","exonStarts","exonEnds"};  
		while (notDone) {
			String[] mapData = mapFile.readOrderedFieldsFromLine(cOrder);
			if (mapData.length==0) {
				notDone=false;
			} else {
				// Use the following Gene constructor:
				// Gene(String chr, int start, int end, String name, String orientation, List<Integer> exonsStart, List<Integer> exonsEnd)
				List<Integer> eStarts = stringToIntList(mapData[6].split(","));
				List<Integer> eEnds = stringToIntList(mapData[7].split(","));
				Gene newGene = new Gene(mapData[1],Integer.parseInt(mapData[2]),Integer.parseInt(mapData[3]),mapData[4],mapData[5],eStarts,eEnds); 
				String symbol = mapData[0];
				if (!geneTable.containsKey(mapData[0])) {
					// new gene symbol:
					geneTable.put(symbol, newGene);
				} else {
					// add this transcript as an isoform, but only if this isoform is on the same chromosome as the first,
					// as well as on the same strand:
					Strand gStrand = geneTable.get(symbol).getStrand();
					String gChrom = geneTable.get(symbol).getChr();
					Strand iStrand = newGene.getStrand();
					String iChrom = newGene.getChr();
					if (gStrand.equals(iStrand) && gChrom.equals(iChrom)) {
						geneTable.get(symbol).addIsoform(newGene);
					} else {
						logger.warn("New isoform mismatch for "+symbol+" ("+gChrom+gStrand+") with "+mapData[4]+" ("+iChrom+iStrand+")");
					}
				}
			}
		}
		
		long midGmapTime = System.nanoTime();    // end map loading, start isoform collapsing timer
		// Collapse all multi-isoform genes down to a single exon set:
		List<String> keySet = new ArrayList<String>(geneTable.keySet());    // since this loop modifies the Hashtable on the fly, it is necessary
													// to extract the keySet first, rather then (String symbol:geneTable.keySet())
		for (String symbol:keySet) {
			Gene thisGene = geneTable.get(symbol);
			Collection<Gene> isoforms = thisGene.getIsoforms();
			if (isoforms.size()==1) {
				//logger.info(symbol+" has only 1 isoform");
				// Set the top-level gene name to the the gene symbol:
				geneTable.get(symbol).setName(symbol);
			} else {
				//logger.info(symbol+" has "+isoforms.size()+" isoforms");
				boolean firstIsoform = true;
				Gene mergedGene = null;
				// merge all isoforms
				for (Gene iso:isoforms) {
					if (firstIsoform) {
						mergedGene = iso;
						firstIsoform = false;
					} else {
						mergedGene = mergedGene.takeUnion(iso);
					}
				}
				// Create a replacement gene where the top-level gene has the union of all exons of all 
				// isoforms.
				// NOTE:: doing it this way because there is no simple way to reset the exons of a gene.
				//        It might be much faster if that method was available.
				// The top-level gene name is the gene symbol, and all isoforms are named by their RefSeq ID (NM_, NR_, etc.)
				// If a gene has a single isoform, the top-level gene and its isoform will be identical, except for the name.
				// constructor: Gene(String chr, int start, int end, String name, String orientation, List<Integer> exonsStart, List<Integer> exonsEnd)
				/* make the exon start/end lists */
				BasicAnnotation[] exons = mergedGene.getExons();
				List<Integer> exonStarts = new ArrayList<Integer>();
				List<Integer> exonEnds = new ArrayList<Integer>();
				for (BasicAnnotation exon:exons) {
					exonStarts.add(exon.getStart());
					exonEnds.add(exon.getEnd());
				}
				// create the new gene:
				Gene newGene = new Gene(thisGene.getChr(),thisGene.getStart(),thisGene.getEnd(),symbol,thisGene.getStrand().toString(),exonStarts,exonEnds);
				// add all of the isoforms:
				for (Gene iso:isoforms) {
					newGene.addIsoform(iso);
				}
				// replace the old gene with the new one:
				geneTable.remove(symbol);
				geneTable.put(symbol, newGene);
			}
		}
		long endGmapTime = System.nanoTime();    // end isoform collapsing timer
		logger.info("Loading the gene-to-isoform map took "+(midGmapTime-startGmapTime)/1e9+" sec.");
		logger.info("           Collapsing the genes took "+(endGmapTime-midGmapTime)/1e9+" sec.");

		return geneTable;
	}
	
	public SAMSequenceCountingDict countReadStartsFromAlignments (SAMSequenceCountingDict bamDict, HashMap<String,ArrayList<File>> bamFiles,
																	boolean qFilter, int qThresh, String multimap, boolean stranded) {
		boolean firstFile = true;      // only read the header from the first alignment file
		int goodQualityCount = 0;
		int badQualityCount = 0;
		int bamFileCount = 0;
		int validReadCount = 0;
		int invalidReadCount = 0;		
		int totalValidReadCount = 0;
		int totalInvalidReadCount = 0;
		SAMRecord r;		
		
		// start file loading timer:
		long startTime = System.nanoTime();

		/* open the input alignments file */
		for (String exp:bamFiles.keySet()) {
			
			for (int i=0; i<bamFiles.get(exp).size(); i++){

				long loopStartTime = System.nanoTime();    // loop timer
			
				// open the next bam file in the list:
				File bamFile = (File) bamFiles.get(exp).get(i);
				logger.info("Processing file: "+bamFile+"...");
				SAMFileReader bamReader = new SAMFileReader(bamFile);   // open as a non-eager reader
				//bamReader.setValidationStringency(ValidationStringency.LENIENT);	
				bamReader.setValidationStringency(ValidationStringency.STRICT);	
				SAMRecordIterator bamIterator = bamReader.iterator();

				if (firstFile) {
					// use the header information in the first bam file to create counts storage
					SAMFileHeader bamHeader = bamReader.getFileHeader();
					//bamDict = new SAMSequenceCountingDict_short();
					
					bamDict.setLogger(logger);
					bamDict.copySequences(bamHeader.getSequenceDictionary());    // copy the sequence map from the original dictionary into the counting dict
					firstFile = false;
				}

				// process each read:
				while (bamIterator.hasNext()) {
					try {
						r = bamIterator.next();
					} catch (SAMFormatException e) {
						// skip SAM Format errors but log a warning:
						logger.warn(e.getMessage());
						continue;
					}
					// process the read:
					if (!r.getReadUnmappedFlag()) {
						// if quality filtering is turned on, skip low-quality reads:
						if (qFilter==true) {
							if (r.getMappingQuality()>qThresh){
								goodQualityCount++;
							} else {
								badQualityCount++;
								continue;
							}
						}
						bamDict.updateCount(r, multimap, stranded);
						// update the read start count
						validReadCount++;
					} else {
						// Skip unmapped reads, but count them 
						invalidReadCount++;
					}
				}

				// close the bam file reader
				bamReader.close();
				bamFileCount++;
				
				// time the loop:
				long loopEndTime = System.nanoTime();
				logger.info("Experiment "+exp+" BAM file "+bamFile+" processed in "+(loopEndTime-loopStartTime)/1e9+" sec\n");
				logger.info("  "+validReadCount+" valid reads\n");
				logger.info("  "+invalidReadCount+" invalid reads");

				// accumulate counts:
				totalValidReadCount+=validReadCount;
				totalInvalidReadCount+=invalidReadCount;
			}
		}
		
		long stopTime = System.nanoTime();
		logger.info(bamFileCount+" BAM files processed in "+(stopTime-startTime)/1e9+" sec\n");
		logger.info("  "+totalValidReadCount+" valid reads\n");
		if (qFilter) {
			//logger.info("     "+goodQualityCount+" reads pass the quality threshold\n");
			logger.info("     "+badQualityCount+" reads fail the quality threshold\n");
		}
		logger.info("  "+totalInvalidReadCount+" invalid reads");
		
		// Return the updated counts dictionary:
		return bamDict;
	}

	public HashMap<String, HashMap<String, IntervalTree<EventCounter>>> makeCountingIntervalTree(HashMap<String,HashMap<String,TranscriptCountInfo>> countsMap, int nExp) {
		// Builds a stranded HashMap of IntervalTrees, one per chromosome
		
		HashMap<String, HashMap<String, IntervalTree<EventCounter>>> cleanTree = new HashMap<String, HashMap<String, IntervalTree<EventCounter>>>();
		// create top-level node for the two strands:
		cleanTree.put("+", new HashMap<String, IntervalTree<EventCounter>>());
		cleanTree.put("-", new HashMap<String, IntervalTree<EventCounter>>());
		
		// keep track of how many non-significant windows there are
		int inWindowCount = 0;

		// Iterate over chromosomes:
		for (String chr:countsMap.keySet()) {
			// Iterate over genes/transcripts:
			for (String gene:countsMap.get(chr).keySet()) {
				// First add all intervals of significant windows:
				ListIterator<Window> wIter = countsMap.get(chr).get(gene).getWindows().listIterator();
				String strand = countsMap.get(chr).get(gene).getStrand();
				while (wIter.hasNext()) {
					Window w = wIter.next();
					inWindowCount++;
					// add a new Window to the cleanCountsMap:
					//strand = w.getStrand();
					// tab-delimited node name:
					String nName = gene+"\t"+chr+"\t"+w.getStart()+"\t"+w.getEnd();
					//String nName = gene+"."+listIdx+" "+chr+":"+w.getStart()+"-"+w.getEnd()+" ("+strand+")";
					if (w.getStart()>=w.getEnd()) {
						logger.warn("start>end for "+gene);
					}

					EventCounter e = new EventCounter(nName, nExp, w);
					// *** TEST: initialize the event counter with the total alignments
					e.setSumCounts(w.getCount());
					if (!cleanTree.get(strand).containsKey(chr)) {
						cleanTree.get(strand).put(chr, new IntervalTree<EventCounter>());
					}
					cleanTree.get(strand).get(chr).put(w.getStart(),w.getEnd(), e);   // add the node to the tree
				}
				// next, add an event counter for intervals of the full gene/transcript to allow accumulation of gene-level counts:
				IntervalTree<String> eTree = countsMap.get(chr).get(gene).getITree();
				Window gWindow = new Window(strand, chr, eTree, gene, nExp);
				EventCounter e = new EventCounter(gene, nExp, gWindow);
				if (!cleanTree.get(strand).containsKey(chr)) {
					cleanTree.get(strand).put(chr, new IntervalTree<EventCounter>());
				}
				cleanTree.get(strand).get(chr).put(gWindow.getStart(), gWindow.getEnd(), e);
			}
		}
		
		logger.info("Total window count: "+inWindowCount);
		
		return cleanTree;
	}
	
	public void fillExperimentWindowCounter(HashMap<String, HashMap<String, IntervalTree<EventCounter>>> windowTree, 
											HashMap<String,ArrayList<File>> bamFiles,
											boolean qFilter,
											int qThresh,
											String multimap,
											boolean stranded) {
		
		SAMRecord r;		// alignment
		String rStrand;		// alignment strand
		String rName;		// alignment name (chromosome)
		int rStart;			// alignment start location
		
		// Get the list of experiment names:
		Object[] expList = bamFiles.keySet().toArray();
		
		// Iterate over the files in each experiment:
		for (int eIdx=0; eIdx<expList.length; eIdx++) {
			
			Object exp = expList[eIdx];
			
			for (int i=0; i<bamFiles.get(exp).size(); i++){

				//long loopStartTime = System.nanoTime();    // loop timer
				
				// open the next bam file in the list:
				File bamFile = (File) bamFiles.get(exp).get(i);
				SAMFileReader bamReader = new SAMFileReader(bamFile);   // open as a non-eager reader
				bamReader.setValidationStringency(ValidationStringency.STRICT);	
				SAMRecordIterator bamIterator = bamReader.iterator();
				logger.info("Processing file: "+bamFile+"...");

				// process each read:
				while (bamIterator.hasNext()) {
					try {
						r = bamIterator.next();
					} catch (SAMFormatException e) {
						// skip SAM Format errors but log a warning:
						logger.warn(e.getMessage());
						continue;
					}
					// process the read:
					if (!r.getReadUnmappedFlag()) {
						// if quality filtering is turned on, skip low-quality reads:
						if (qFilter==true) {
							if (!(r.getMappingQuality()>qThresh)) {
								// skip bad reads
								continue;
							}
						}
						// Update the counts in cleanCountsMap if the read start location is contained in
						// an interval in the windowTree.
					   	rName = r.getReferenceName();                // chromosome ID
				    	rStart = (int)(r.getAlignmentStart())-1;   // alignments are 1-based, arrays are 0-based
				    	if (stranded & r.getReadNegativeStrandFlag()) {
				    		rStrand = "-";
				    	} else {
				    		rStrand = "+";
				    	}
				    	String cString = r.getCigarString(); 
				    	// Note: if the CigarString is "*", it indicates that the read is unmapped. It would be better 
				    	//       if SAMRecord had a isMapped() method.
				    	if (cString!="*") {
				    		// Deal with multimapped reads:
				    		float fractCount;
				    		if (multimap.equals("normal")) {
				    			fractCount=1;
				    		} else if (multimap.equals("ignore")) {
				    			int mmCount = SAMSequenceCountingDict.getMultimapCount(r);
				    			if (mmCount==1) {
				    				fractCount=1;
				    			} else {
				    				fractCount=0;   // hacky way to skip reads... 
				    			}
				    		} else {
				    			// scaled mulitmapped reads:
				    			int mmCount = SAMSequenceCountingDict.getMultimapCount(r);
				    			fractCount=1f/mmCount;
				    		}

				    		// check if this read start is contained in any intervals in the tree:
				    		if (windowTree.get(rStrand).containsKey(rName) && windowTree.get(rStrand).get(rName).numOverlappers(rStart, rStart+1)>0) {
				    			Iterator<IntervalTree.Node<EventCounter>> oIter = windowTree.get(rStrand).get(rName).overlappers(rStart,rStart+1);
				    			//logger.info("Read at "+rName+":"+rStart+" ("+rStrand+
				    			//		") has "+windowTree.get(rStrand).get(rName).numOverlappers(rStart, rStart+1)+" overlapping intervals");
				    			while (oIter.hasNext()) {
				    				Node<EventCounter> n = oIter.next();
				    				// This node might contain multiple EventCounters. Update them all:
				    				Collection<EventCounter> cvNode = n.getContainedValues();
				    				for (EventCounter e:cvNode) {
				    					// 	update the count for this interval:
				    					//n.getValue().addIntervalCount(rStart, rStart+1, eIdx, fractCount);   // add (possibly) fractional counts if read is contained in an interval
				    					e.addIntervalCount(rStart, rStart+1, eIdx, fractCount);   // add (possibly) fractional counts if read is contained in an interval
				    				}
				    			}
				    		}
				    	}
					}
				}
			}
		}
	} 	
}