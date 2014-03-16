package edu.stanford.nlp.mt.tools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;

import edu.stanford.nlp.mt.base.IOTools;
import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.IStrings;
import edu.stanford.nlp.mt.base.InsertedStartEndToken;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.lm.ARPALanguageModel;
import edu.stanford.nlp.mt.lm.LanguageModel;
import edu.stanford.nlp.mt.lm.LanguageModelFactory;

/**
 * Evaluate the perplexity of an input file under a language model.
 * 
 * @author danielcer
 *
 */
public final class LanguageModelPerplexity {
  
  private LanguageModelPerplexity() {}
  
  public static <T> double scoreSequence(LanguageModel<T> lm, Sequence<T> sequence) {
    double logP = 0;
    Sequence<T> paddedSequence = new InsertedStartEndToken<T>(sequence, lm.getStartToken(),
        lm.getEndToken());
    for (int i = 1, limit = paddedSequence.size(); i < limit; i++) {
      final int seqStart = Math.max(0, i - lm.order() + 1);
      Sequence<T> ngram = paddedSequence.subsequence(seqStart, i + 1);
      double ngramScore = lm.score(ngram).getScore();
      if (ngramScore == ARPALanguageModel.UNKNOWN_WORD_SCORE) {
        // like sri lm's n-gram utility w.r.t. closed vocab models,
        // right now we silently ignore unknown words.
        continue;
      }
      logP += ngramScore;
    }
    return logP;
  }
  
  /**
   * 
   * @param args
   */
  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      System.err
          .printf("Usage: java %s type:path [input_file] < input_file%n", LanguageModelPerplexity.class.getName());
      System.exit(-1);
    }

    String model = args[0];
    System.out.printf("Loading lm: %s...%n", model);
    LanguageModel<IString> lm = LanguageModelFactory.load(model);

    LineNumberReader reader = (args.length == 1) ? 
        new LineNumberReader(new InputStreamReader(System.in)) :
          IOTools.getReaderFromFile(args[1]);
    
    double logSum = 0.0;
    final long startTimeMillis = System.nanoTime();
    for (String sent; (sent = reader.readLine()) != null;) {
      Sequence<IString> seq = IStrings.tokenize(sent);
      final double score = scoreSequence(lm, seq);
      assert score != 0.0;
      assert ! Double.isNaN(score);
      assert ! Double.isInfinite(score);
      
      logSum += score;
      
      System.out.println("Sentence: " + sent);
      System.out.printf("Sequence score: %f score_log10: %f%n", score, score
          / Math.log(10));
    }
    reader.close();
    System.out.printf("Log sum score: %e%n", logSum);
        
    double elapsed = (System.nanoTime() - startTimeMillis) / 1e9;
    System.err.printf("Elapsed time: %.3fs%n", elapsed);
  }
}
