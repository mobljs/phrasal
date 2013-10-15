package edu.stanford.nlp.mt.wordcls;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.stanford.nlp.mt.base.IOTools;
import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.IStrings;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.log.PhrasalLogger;
import edu.stanford.nlp.mt.log.PhrasalLogger.LogName;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.stats.TwoDimensionalCounter;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.concurrent.MulticoreWrapper;
import edu.stanford.nlp.util.concurrent.ThreadsafeProcessor;

/**
 * Various algorithms for learning a mapping function from an input
 * word to an output equivalence class.
 * 
 * TODO Add encoding parameter
 * TODO Extract out objective function as an interface to support
 * other clustering algorithms if needed.
 * 
 * @author Spence Green
 *
 */
public class MakeWordClasses {

  private static final IString START_TOKEN = new IString("<s>");
  
  private final int numIterations;
  private final int numClasses;
  private final int numThreads;
  private final int vparts;
  private final int order;
  
  private final Logger logger;
  
  private static enum OutputFormat {SRILM, TSV};
  
  private static final int INITIAL_CAPACITY = 100000;
  private final Map<IString,Integer> wordToClass;
  private final Counter<IString> wordCount;
  private final TwoDimensionalCounter<IString, NgramHistory> historyCount;
  private TwoDimensionalCounter<Integer,NgramHistory> classHistoryCount;
  private final ClassicCounter<Integer> classCount;
  private final OutputFormat outputFormat;
  
  private double currentObjectiveValue = 0.0;
  
  public MakeWordClasses(Properties properties) {
    // User options
    this.numIterations = PropertiesUtils.getInt(properties, "niters", 20);
    assert this.numIterations > 0;
    
    this.numClasses = PropertiesUtils.getInt(properties, "nclasses", 512);
    assert this.numClasses > 0;
    
    this.numThreads = PropertiesUtils.getInt(properties, "nthreads", 1);
    assert this.numThreads > 0;
    
    this.vparts = PropertiesUtils.getInt(properties, "vparts", 3);
    assert this.vparts > 0;
    
    this.order = PropertiesUtils.getInt(properties, "order", 2);
    assert this.order > 1;
    
    this.outputFormat = OutputFormat.valueOf(
        properties.getProperty("format", OutputFormat.TSV.toString()).toUpperCase());
    
    logger = Logger.getLogger(this.getClass().getName());
    PhrasalLogger.logLevel = Level.FINE;
    SimpleDateFormat sdf = new SimpleDateFormat("HH-mm-ss");
    PhrasalLogger.prefix = String.format("%d-classes.%s", numClasses, sdf.format(new Date()));
    PhrasalLogger.attach(logger, LogName.WORD_CLASS);
    logger.info("#iterations: " + String.valueOf(numIterations));
    logger.info("#classes: " + String.valueOf(numClasses));
    logger.info("order: " + String.valueOf(order));
    
    // Internal data structures
    wordToClass = Generics.newHashMap(INITIAL_CAPACITY);
    wordCount = new ClassicCounter<IString>(INITIAL_CAPACITY);
    classCount = new ClassicCounter<Integer>(numClasses);
    historyCount = new TwoDimensionalCounter<IString,NgramHistory>();
    classHistoryCount = new TwoDimensionalCounter<Integer,NgramHistory>();
  }

  private void initialize(String[] filenames) throws IOException {
    List<IString> defaultHistory = Generics.newLinkedList();
    for (int i = 0; i < order-1; ++i) {
      defaultHistory.add(START_TOKEN);
    }
    // Read the vocabulary and histories
    final long startTime = System.nanoTime();
    for (String filename : filenames) {
      logger.info("Reading: " + filename);
      LineNumberReader reader = IOTools.getReaderFromFile(filename);
      for (String line; (line = reader.readLine()) != null;) {
        Sequence<IString> tokens = IStrings.tokenize(line.trim());
        List<IString> history = Generics.newLinkedList(defaultHistory);
        for (IString token : tokens) {
          wordCount.incrementCount(token);
          historyCount.incrementCount(token, new NgramHistory(history));
          history.add(token);
          history.remove(0);
        }
      }
      reader.close();
    }
    NgramHistory.lockIndex();
    final double elapsedTime = ((double) System.nanoTime() - startTime) / 1e9;
    logger.info(String.format("Done reading input files (%.3fsec)", elapsedTime));
    logger.info(String.format("Input gross statistics: %d words  %d tokens  %d histories", 
        wordCount.keySet().size(), (int) wordCount.totalCount(), (int) historyCount.totalCount()));
    
    // Initialize clustering
    List<IString> vocab = Generics.newArrayList(wordCount.keySet());
    Collections.sort(vocab, Counters.toComparator(wordCount, false, true));
    for (int i = 0; i < vocab.size(); ++i) {
      final int classId = i % numClasses;
      IString word = vocab.get(i);
      classCount.incrementCount(classId, wordCount.getCount(word));
      wordToClass.put(word, classId);
      Counter<NgramHistory> historiesForWord = historyCount.getCounter(word);
      for (NgramHistory h : historiesForWord.keySet()) {
        double count = historiesForWord.getCount(h);
        classHistoryCount.incrementCount(classId, h, count);
      }
    }
    currentObjectiveValue = objectiveFunctionValue();
    logger.info("Finished generating initial cluster assignment");
    logger.info(String.format("Initial objective function value: %.3f%n", currentObjectiveValue));
  }

  /**
   * Create word clusters from the list of input files.
   * 
   * @param filenames
   */
  public void run(String[] filenames) {
    try {
      initialize(filenames);
    } catch (IOException e1) {
      throw new RuntimeException(e1);
    }
    
    logger.info(String.format("Starting clustering with %d threads", numThreads));
    final List<IString> fullVocabulary = Generics.newArrayList(wordCount.keySet());
    Collections.shuffle(fullVocabulary);

    for (int e = 0; e < numIterations; ++e) {
      MulticoreWrapper<ClustererState,PartialStateUpdate> threadpool = 
          new MulticoreWrapper<ClustererState,PartialStateUpdate>(numThreads, 
              new ThreadsafeProcessor<ClustererState,PartialStateUpdate>() {
                @Override
                public PartialStateUpdate process(ClustererState input) {
                  OneSidedObjectiveFunction algorithm = new OneSidedObjectiveFunction(input);
                  return algorithm.cluster();
                }
                @Override
                public ThreadsafeProcessor<ClustererState, PartialStateUpdate> newInstance() {
                  return this;
                }
          });
      // Select partition and dispatch workers
      final int partitionNumber = e % vparts;
      logger.info(String.format("Iteration %d: partition %d start", e, partitionNumber));
      final long startTime = System.nanoTime();
      for (int t = 0; t < numThreads; ++t) {
        ClustererState input = createInput(fullVocabulary, partitionNumber, t);
        threadpool.put(input);
      }

      // Wait for shutdown and process results
      threadpool.join();
      int numUpdates = 0;
      while(threadpool.peek()) {
        PartialStateUpdate result = threadpool.poll();
        numUpdates += updateCountsWith(result);
      }
      double elapsedTime = ((double) System.nanoTime() - startTime) / 1e9;
      logger.info(String.format("Iteration %d: elapsed time %.3fsec", e, elapsedTime));
      logger.info(String.format("Iteration %d: #updates %d", e, numUpdates));
      logger.info(String.format("Iteration %d: objective: %.4f", e, objectiveFunctionValue()));
    }
  }
  
  /**
   * Objective function of Uszkoreit and Brants (2008) (Eq. 10).
   * 
   * @return
   */
  private double objectiveFunctionValue() {
    // Clean up counters
    classHistoryCount.clean();
    Counters.retainNonZeros(classCount);
    
    double objValue = 0.0;
    for (int classId = 0; classId < numClasses; ++classId) {
      Counter<NgramHistory> historyCount = classHistoryCount.getCounter(classId);
      for (NgramHistory history : historyCount.keySet()) {
        double count = historyCount.getCount(history);
        assert count > 0.0;
        objValue += count * Math.log(count);
      }
      double count = classCount.getCount(classId);
      if (count > 0.0) {
        objValue -= count * Math.log(count);
      } else {
        logger.warning("Empty cluster: " + String.valueOf(classId));
      }
    }
    return objValue;
  }

  private ClustererState createInput(List<IString> fullVocabulary, int partitionNumber, int threadId) {
    int partitionSize = fullVocabulary.size() / vparts;
    int partitionStart = partitionNumber*partitionSize;
    int partitionEnd = partitionNumber == vparts-1 ? fullVocabulary.size() : (partitionNumber+1)*partitionSize;
    partitionSize = partitionEnd-partitionStart;
    
    int inputSize = partitionSize / numThreads;
    int inputStart = threadId * inputSize;
    int inputEnd = threadId == numThreads-1 ? partitionEnd : partitionStart + ((threadId+1)*inputSize);
    logger.info(String.format("Partition %d thread %d size %d: input %d-%d", partitionNumber,
        threadId, partitionSize, partitionStart+inputStart, inputEnd-1));
    List<IString> inputVocab = fullVocabulary.subList(partitionStart + inputStart, inputEnd);
    return new ClustererState(inputVocab, this.wordCount, 
        this.historyCount, this.wordToClass, this.classCount, this.classHistoryCount,
        this.numClasses, this.currentObjectiveValue);
  }

  private int updateCountsWith(PartialStateUpdate result) {
    // Update counts
    Counters.addInPlace(classCount, result.deltaClassCount);
    Set<Integer> classes = result.deltaClassHistoryCount.firstKeySet();
    for (Integer classId : classes) {
      Counter<NgramHistory> counter = this.classHistoryCount.getCounter(classId);
      Counter<NgramHistory> delta = result.deltaClassHistoryCount.getCounter(classId);
      Counters.addInPlace(counter, delta);
    }
    
    // Update assignments
    int numUpdates = 0;
    for (Map.Entry<IString, Integer> assignment : result.wordToClass.entrySet()) {
      if (wordToClass.get(assignment.getKey()) != assignment.getValue()) {
        ++numUpdates;
        wordToClass.put(assignment.getKey(), assignment.getValue());
      }
    }
    return numUpdates;
  }

  /**
   * Write the final cluster assignments to the specified output stream.
   * 
   * @param out
   */
  public void writeResults(PrintStream out) {
    logger.info(String.format("Writing final class assignments in %s format",
        outputFormat.toString()));
    for (Map.Entry<IString, Integer> assignment : wordToClass.entrySet()) {
      if (outputFormat == OutputFormat.TSV) {
        out.printf("%s\t%d%n", assignment.getKey().toString(), assignment.getValue());
      
      } else if (outputFormat == OutputFormat.SRILM) {
        out.printf("%d 1.0 %s%n", assignment.getValue(), assignment.getKey().toString());
      }
    }
  }

  private static Map<String, Integer> optionArgDefs() {
    Map<String,Integer> argDefs = Generics.newHashMap();
    argDefs.put("order", 1);
    argDefs.put("nthreads", 1);
    argDefs.put("nclasses", 1);
    argDefs.put("niters", 1);
    argDefs.put("vparts", 1);
    argDefs.put("format", 1);
    return argDefs;
  }
  
  private static String usage() {
    StringBuilder sb = new StringBuilder();
    final String nl = System.getProperty("line.separator");
    sb.append("Usage: java ").append(MakeWordClasses.class.getName()).append(" OPTS file [file] > output").append(nl)
    .append(" -order num     : Model order (default: 2)").append(nl)
    .append(" -nthreads num  : Number of threads (default: 1)").append(nl)
    .append(" -nclasses num  : Number of classes (default: 512)").append(nl)
    .append(" -niters num    : Number of iterations (default: 20)").append(nl)
    .append(" -vparts num    : Number of vocabulary partitions (default: 3)").append(nl)
    .append(" -format type   : Output format [srilm|tsv] (default: tsv)");

    return sb.toString();
  }
  
  /**
   * @param args
   */
  public static void main(String[] args) {
    Properties options = StringUtils.argsToProperties(args, optionArgDefs());
    String[] filenames = options.getProperty("","").split("\\s+");
    if (filenames.length < 1 || filenames[0].length() == 0 || options.containsKey("h")
        || options.containsKey("help")) {
      System.err.println(usage());
      System.exit(-1);
    }
    MakeWordClasses mkWordCls = new MakeWordClasses(options);
    mkWordCls.run(filenames);
    mkWordCls.writeResults(System.out);
  }
}