package org.clulab.serialization.json

import java.io.File

import org.clulab.processors.DocumentAttachmentBuilderFromJson
import org.clulab.processors.{Document, Sentence}
import org.clulab.struct.{DirectedGraph, GraphMap}
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.prettyJson


/** JSON serialization utilities */
object JSONSerializer {

  implicit val formats = DefaultFormats

  def jsonAST(f: File): JValue = {
    val source = scala.io.Source.fromFile(f)
    val contents = source.getLines.mkString
    source.close()
    parse(contents)
  }

  protected def addDocumentAttachments(doc: Document, jValue: JValue): Unit = {
    // See also DocumentSerializer for text version of nearly the same thing.
    (jValue \ DOCUMENT_ATTACHMENTS_KEY) match {
      case jObject: JObject =>
        val keys = jObject.values.keys
        keys.foreach { key: String =>
          (jObject \ key) match {
            case jObject: JObject =>
              val documentAttachmentBuilderFromJsonClassName = (jObject \ DOCUMENT_ATTACHMENTS_BUILDER_KEY).extract[String]
              val clazz = Class.forName(documentAttachmentBuilderFromJsonClassName)
              val ctor = clazz.getConstructor()
              val obj = ctor.newInstance()
              val documentAttachmentBuilder = obj.asInstanceOf[DocumentAttachmentBuilderFromJson]
              val value = (jObject \ DOCUMENT_ATTACHMENTS_VALUE_KEY)
              val documentAttachment = documentAttachmentBuilder.mkDocumentAttachment(value)
              doc.addAttachment(key, documentAttachment)
            case jValue: JValue =>
              val text = prettyJson(jValue)
              throw new RuntimeException(s"ERROR: While deserializing document attachments expected JObject but found this: $text")
            case _ => // noop.  It should never get here.  (Famous last words.)
          }
        }
      case _ => // Leave documentAttachments as is: None
    }
  }

  def toDocument(json: JValue): Document = {
    // recover sentences
    val sentences = (json \ "sentences").asInstanceOf[JArray].arr.map(sjson => toSentence(sjson)).toArray
    // initialize document
    val d = Document(sentences)
    // update id
    d.id = getStringOption(json, "id")
    // update text
    d.text = getStringOption(json, "text")
    addDocumentAttachments(d, json)
    d
  }
  def toDocument(docHash: String, djson: JValue): Document = toDocument(djson \ docHash)
  def toDocument(f: File): Document = toDocument(jsonAST(f))

  def toSentence(json: JValue): Sentence = {

    def getLabels(json: JValue, k: String): Option[Array[String]] = json \ k match {
      case JNothing => None
      case contents => Some(contents.extract[Array[String]])
    }

    val s = json.extract[Sentence]
    // build dependencies
    val graphs = (json \ "graphs").extract[Map[String, DirectedGraph[String]]]
    s.graphs = GraphMap(graphs)
    // build labels
    s.tags = getLabels(json, "tags")
    s.lemmas = getLabels(json, "lemmas")
    s.entities = getLabels(json, "entities")
    s.norms = getLabels(json, "norms")
    s.chunks = getLabels(json, "chunks")
    s
  }

  private def getStringOption(json: JValue, key: String): Option[String] = json \ key match {
    case JString(s) => Some(s)
    case _ => None
  }
}
