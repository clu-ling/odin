package org.clulab.processors.clu.sequences

import java.io._
import java.util
import java.util.regex.Pattern

import cc.mallet.pipe.Pipe
import cc.mallet.types._
import org.clulab.processors.{Document, Sentence}
import CRFSequenceTagger._
import SequenceTaggerLogger._
import cc.mallet.fst.{CRF, CRFTrainerByThreadedLabelLikelihood}
import cc.mallet.fst.SimpleTagger._
import cc.mallet.pipe.iterator.LineGroupIterator
import org.clulab.struct.Counter

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

/**
  * Generic sequence tagger over words implemented using the mallet CRF
  * Author: mihais
  * Date: 3/24/17
  */
abstract class CRFSequenceTagger[L, F] extends SequenceTagger[L, F] {
  var crfModel:Option[CRF] = None
  var testPipe:Option[ToFeatureVectorPipe[F]] = None

  val allowable = new mutable.HashMap[String, mutable.HashSet[L]]

  def preprocess(docs:Iterator[Document]) {
    for(doc <- docs; sentence <- doc.sentences) {
      val labels = labelExtractor(sentence)
      val words = sentence.words.map(_.toLowerCase())
      assert(words.length == labels.length)

      for(i <- words.indices) {
        addAllowable(words(i), labels(i))
      }
    }
  }

  def addAllowable(word:String, label:L) {
    val aw = allowable.getOrElseUpdate(word, new mutable.HashSet[L]())
    aw += label
  }

  override def train(docs:Iterator[Document]) {
    // generate features for all sentences in all docs, and save them to disk
    val f = File.createTempFile("sequence_tagger", ".train")
    val pw = new PrintWriter(new FileWriter(f))
    for(doc <- docs; sentence <- doc.sentences) {
      // labels and features for one sentence
      val labels = labelExtractor(sentence)
      val features = (0 until sentence.size).map(mkFeatures(sentence, _)).toArray

      // save this sentence to disk; features first, then labels
      assert(features.length == labels.length)
      for(i <- labels.indices) {
        pw.println(s"${features(i).mkString(" ")} ${labels(i)}")
      }
      pw.println()
    }
    logger.info(s"Saved training file ${f.getAbsolutePath}")
    pw.close()

    // actual CRF training
    trainCRF(f)

    // cleanup
    f.delete()
    logger.info(s"Deleted temporary training file ${f.getAbsolutePath}")
  }

  private def trainCRF(trainFile:File):Boolean = {
    // read training data from file
    val pipe = new SimpleTaggerSentence2FeatureVectorSequence
    // pipe.getTargetAlphabet.lookupIndex(defaultLabel) // TODO: is this actually needed?
    pipe.setTargetProcessing(true)
    val trainingData = new InstanceList(pipe)
    trainingData.addThruPipe(new LineGroupIterator(new FileReader(trainFile), Pattern.compile("^\\s*$"), true))

    // some logging
    if (pipe.isTargetProcessing) {
      val targets = pipe.getTargetAlphabet
      val buf = new StringBuilder("Training for labels:")
      for (i <- 0 until targets.size) buf.append(" " + targets.lookupObject(i).toString)
      logger.info(buf.toString)
    }

    // initialize the CRF
    val crf = new CRF(trainingData.getPipe, null.asInstanceOf[Pipe])

    //
    // different ways to construct the CRF:
    //

    // first-order CRF that creates many transitions + factors, and is MUCH slower to train
    /*
    val startName = crf.addOrderNStates(trainingData, orders, null, defaultLabel, forbiddenPattern, allowedPattern, fullyConnected)
    for (i <- 0 until crf.numStates()) {
      crf.getState(i).setInitialWeight(Transducer.IMPOSSIBLE_WEIGHT)
    }
    crf.getState(startName).setInitialWeight(0.0)
    */

    // first-order CRF, with the minimal number of factors; trains fast, performs well
    crf.addStatesForThreeQuarterLabelsConnectedAsIn(trainingData)

    // second-order CRF; TODO
    //crf.addStatesForBiLabelsConnectedAsIn(trainingData)

    logger.info(s"Training on ${trainingData.size()} instances.")

    // the actual training
    val crft = new CRFTrainerByThreadedLabelLikelihood(crf, numThreads)
    crft.setGaussianPriorVariance(gaussianVariance)
    crft.setUseSparseWeights(true)
    crft.setUseSomeUnsupportedTrick(true)
    // these 2 lines above correspond to the "some-dense" SimpleTagger option
    var converged = false
    for (i <- 1 to iterations if !converged) {
      logger.info(s"Training iteration #$i...")
      converged = crft.train(trainingData, 1)

      if(i % 2 == 0) {
        val dfn = trainFile.getAbsolutePath + s".model.$i"
        logger.info(s"Saving intermediate model file to $dfn...")
        val os = new ObjectOutputStream(new FileOutputStream(dfn))
        os.writeObject(crf)
        os.close()
        logger.info("Saved successfully.")
      }
    }
    crft.shutdown()

    // keep the model
    crfModel = Some(crf)
    testPipe = Some(new ToFeatureVectorPipe[F](crf.getInputAlphabet, crf.getOutputAlphabet))
    testPipe.get.setTargetProcessing(false)
    true
  }

  def mkFeatures(sentence: Sentence, offset:Int): Set[F] = {
    val fs = new Counter[F]()
    featureExtractor(fs, sentence, offset)
    // We discard all counter values here! Not sure how to add feature values to Mallet
    fs.toSet
  }

  override def classesOf(sentence: Sentence):List[L] = {
    assert(crfModel.isDefined)
    assert(testPipe.isDefined)

    // convert the sentence into 1 mallet Instance
    val features = (0 until sentence.size).map(mkFeatures(sentence, _)).toArray
    val instance = new Instance(features, null, "test sentence", null)
    val instances = new util.ArrayList[Instance]()
    instances.add(instance)

    // add the Instance corresponding to this sentence to the testData
    val testData = new InstanceList(testPipe.get)
    testData.addThruPipe(instances.iterator)

    // run the CRF on this Instance
    val input = testData.get(0).getData.asInstanceOf[FeatureVectorSequence]
    val output = crfModel.get.transduce(input)
    assert(output.size == sentence.size)
    val labels = new ListBuffer[L] // (output.size)
    for(i <- 0 until output.size) {
      labels += output.get(i).asInstanceOf[L]
    }

    //println(s"LABELS: ${labels.mkString(", ")}")
    labels.toList
  }

  override def save(fn:File) {
    assert(crfModel.isDefined)
    val os = new ObjectOutputStream(new FileOutputStream(fn))
    os.writeObject(crfModel.get)
    os.close()
  }

  override def load(is:InputStream) {
    val s = new ObjectInputStream(is)
    val model = s.readObject.asInstanceOf[CRF]
    s.close()
    crfModel = Some(model)
    testPipe = Some(new ToFeatureVectorPipe(model.getInputAlphabet, model.getOutputAlphabet))
    testPipe.get.setTargetProcessing(false)
  }
}

class ToFeatureVectorPipe[F](featureAlphabet:Alphabet, labelAlphabet:Alphabet) extends Pipe(featureAlphabet, labelAlphabet)  {
  override def pipe(carrier: Instance):Instance = {
    val data = carrier.getData
    assert(data.isInstanceOf[Array[Set[F]]])
    val dataFeats = data.asInstanceOf[Array[Set[F]]]
    val fvs = new Array[FeatureVector](dataFeats.length)

    /*
    println("FEATURES:")
    for(i <- dataFeats.indices) {
      print(s"#$i:")
      for(f <- dataFeats(i)) print(s" ${f}")
      println
    }
    */

    for(i <- dataFeats.indices) {
      //print(s"#$i:")
      val feats = dataFeats(i)
      val featureIndices = new ArrayBuffer[Int]
      for(f <- feats) {
        val fi = featureAlphabet.lookupIndex(f)
        if(fi != -1) {
          featureIndices += fi
          //print(s" ${fi}")
        }
      }
      //println
      val featureVector = new FeatureVector(featureAlphabet, featureIndices.toArray)
      fvs(i) = featureVector
    }
    carrier.setData(new FeatureVectorSequence(fvs))
    carrier.setTarget(new LabelSequence(getTargetAlphabet))
    carrier
  }
}

object CRFSequenceTagger {
  //
  // Default options taken from SimpleTagger
  //
  val defaultLabel = "O"
  // label1,label2 transition forbidden if it matches this
  val forbiddenPattern: Pattern = Pattern.compile("\\s")
  // label1,label2 transition allowed only if it matches this
  val allowedPattern: Pattern = Pattern.compile(".*")
  // list of label Markov orders (main and backoff)
  val orders: Array[Int] = Array(1)
  // number of training iterations
  val iterations = 1000
  // include all allowed transitions, even those not in training data
  val fullyConnected = true
  // the gaussian prior variance used for training
  val gaussianVariance = 10.0
  // how many threads to use during training
  val numThreads = 4
}
