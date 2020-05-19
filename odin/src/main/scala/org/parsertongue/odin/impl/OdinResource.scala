package org.parsertongue.odin.impl

import org.parsertongue.odin.embeddings.EmbeddingModel
import java.io.InputStream


trait OdinResource

// for distributional similarity comparisons
// Uses Word2Vec class as its backend
class EmbeddingsResource(is: InputStream) extends EmbeddingModel with OdinResource {
  def similarity(w1: String, w2: String): Double = {
    // FIXME: implement me
    //val score = similarity(w1, w2)
    val score = 0
      //println(s"score is $score")
      score
  }
}