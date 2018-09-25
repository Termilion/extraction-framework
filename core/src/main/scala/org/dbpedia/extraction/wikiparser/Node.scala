package org.dbpedia.extraction.wikiparser

import org.apache.log4j.Level
import org.dbpedia.extraction.annotations.WikiNodeAnnotation
import org.dbpedia.extraction.config._
import org.dbpedia.extraction.config.provenance.NodeRecord

import scala.collection.mutable
import org.dbpedia.extraction.util.StringUtils.{escape, replacements}
import org.dbpedia.extraction.util.{Language, WikiUtil}
import org.dbpedia.iri.IRI

import scala.collection.mutable.ListBuffer

/**
 * Base class of all nodes in the abstract syntax tree.
 * 
 * This class is NOT thread-safe.
 */
trait Node extends Serializable with Recordable[Node]
{
    def children : List[Node]

    val line : Int

    /**
     * CAUTION: code outside this class should change the parent only under very rare circumstances.
     */
    var parent: Node = _
    
    //Set the parent of all children
    for(child <- children) child.parent = this

    private val annotations = new mutable.HashMap[AnnotationKey[_], Any]()

  /**
    * TODO the UriGenerator class needs to be replaced (see bottom)
    */
    private val uriGenerator = new UriGenerator()

    def generateUri(baseUri : String, node : Node): String = uriGenerator.generate(baseUri, node)

    def generateUri(baseUri : String, name : String): String = uriGenerator.generate(baseUri, name)

    /**
     * Convert back to original (or equivalent) wiki markup string.
     */
    def toWikiText: String

    /**
     * Get plain text content of this node and all child nodes, without markup. Since templates
     * are not expanded, this will not work well for templates.
     */
    def toPlainText: String

    /**
     *  Retrieves the root node of this AST.
     *
     * @return The root Node
     */
    lazy val root : PageNode =
    {
      var node = this

      while(node.parent != null)
      {
          node = node.parent
      }

      node.asInstanceOf[PageNode]
    }
   
    /**
     * Retrieves the section of this node
     * 
     * @return The section of this node, may be null
     */
    lazy val section : SectionNode = findSection
    
    private def findSection : SectionNode = {
      
      var section : SectionNode = null

      for(node <- root.children)
      {
        if(node.line > line)
            return section

        node match {
          case n: SectionNode => section = n
          case _ =>
        }
      }

      section
    }

    /**
     * Retrieves some text from this node. Only works on a TextNode or a Node that has 
     * a single TextNode child. Returns None iff this node is not a TextNode and contains 
     * child nodes other than a single TextNode.
     * 
     * TODO: the behavior of this method is weird, but I don't dare to change it because existing
     * code may rely on its current behavior. New code should probably use toPlainText.
     */
    final def retrieveText: Option[String] = retrieveText(true)

    protected def retrieveText(recurse: Boolean): Option[String] = {
      if (recurse && children.length == 1) children.head.retrieveText(false) else None
    }

    /**
     * Returns an annotation.
     *
     * @param key key of the annotation
     * @return The value of the annotation as an option if an annotation with the given key exists. None, otherwise.
     */
    @unchecked // we know the type is ok - setAnnotation doesn't allow any other type
    def getAnnotation[T](key: AnnotationKey[T]): Option[T] = {
      annotations.get(key).asInstanceOf[Option[T]]
    }

    /**
     * Sets a user-defined annotation.
     *
     * @param key The key of the annotation
     * @param value The value of the annotation
     */
    def setAnnotation[T](key: AnnotationKey[T], value: T): Unit = {
      annotations(key) = value
    }


    /**
      * reflects the software agent annotation of an extractor class as an iri (e.g.:
      * http://dbpedia.org/extractor/InfoboxExtractor?githash=fa56dddb6df1b97269c14ce25430729a12775549 )
      */
    lazy val wikiNodeAnnotation: IRI = WikiNodeAnnotation.getAnnotationIri(this.getClass)

  /**
    * sub classes that add new fields should override this method
    */
  override def hashCode(): Int = {
    val prime = 41
    var hash = root.revision.hashCode()
    hash = prime*hash + line.hashCode()
    hash = prime*hash + toWikiText.hashCode
    hash
  }

  /**
    * Collects every template matching the names provided (or all if none are provided)
    * @param names - template names to look for
    * @return - template names of nodes within
    */
  def containedTemplateNames(names : Set[String] = Set.empty): List[String] ={
    Node.collectTemplates(this, names).map(x => x.title.encoded)
  }

  /**
    * Collects every template matching the names provided (or all if none are provided)
    * @param names - template names to look for
    * @return - template nodes within
    */
  def containedTemplateNodes(names : Set[String] = Set.empty): List[TemplateNode] ={
    Node.collectTemplates(this, names)
  }
    
    /**
     * IRI of root source page
    * containing revision no and namespace
     */
  lazy val sourceIri : String =
  {
    val sb = new java.lang.StringBuilder

    sb append root.title.pageIri
    if (root.revision >= 0) {
      sb append "?oldid=" append root.revision
      sb append "&ns=" append root.title.namespace.code
    }
    sb.toString
  }

  /**
    * Returns a list of all templates matching the names provided (or all)
    * @param names - template names to match
    * @return
    */
  def hasTemplate(names : Set[String] = Set.empty) : Boolean = this.containedTemplateNodes(names).nonEmpty

  /**
    * Creates a NodeRecord metadata object of this node
    * @return
    */
  def getNodeRecord: NodeRecord

  private var extractionRecords: ListBuffer[RecordEntry[Node]] = _
  override def recordEntries: Seq[RecordEntry[Node]] = {
      extractionRecords
  }

  private[extraction] def addExtractionRecord(recordEntry: RecordEntry[_]): Unit ={
    assert(recordEntry != null)
    if(extractionRecords == null)
      extractionRecords = new ListBuffer[RecordEntry[Node]]()
    recordEntry match{
      case re: RecordEntry[Node] => extractionRecords.append(re)
      case de: RecordEntry[DefaultEntry] => extractionRecords.append(new RecordEntry[Node](this, Option(de.language).getOrElse(Language.None), de.msg, de.error, de.level))
      case _ =>
    }
  }


  override def toString = toWikiText

  /**
    * Will produce negative longs based on the hash code
    * TODO find better id management for dependent nodes
    * NOTE: Override if unique id exists
    * negative ids are not guaranteed to be unique
    */
  override def id: Long = Long.MinValue + Int.MaxValue + this.hashCode()
}

object Node {
  // For this list of characters, see ifragment in RFC 3987 and 
  // https://sourceforge.net/mailarchive/message.php?msg_id=28982391
  // Only difference to ipchar: don't escape '?'. We don't escape '/' anyway.
  val fragmentEscapes: Array[String] = {
    val chars = ('\u0000' to '\u001F').mkString + "\"#%<>[\\]^`{|}" + ('\u007F' to '\u009F').mkString
    val replace = replacements('%', chars)
    // don't escape space, replace it by underscore
    replace(' ') = "_"
    replace
  }

  def collectTemplates(node : Node, names : Set[String]) : List[TemplateNode] = {
    val ts = new ListBuffer[TemplateNode]()
    node match {
      case tn: TemplateNode if names.isEmpty => ts.append(tn)
      case tn: TemplateNode if names.contains(tn.title.decoded) => ts.append(tn)
      case _ =>
    }
    node.children.foreach(n => ts.appendAll(collectTemplates(n, names)))
    ts.toList
  }
}


private class UriGenerator
{
    var uris: Map[String, Int] = Map[String, Int]()

    def generate(baseUri : String, node : Node) : String =
    {
        node match
        {
            case _ : Node =>
            val sb = new StringBuilder()
              nodeToString(node, sb)
              generate(baseUri, sb.toString())
            case null => generate(baseUri, "") //TODO forbid node==null ?
        }
    }

    def generate(baseUri : String, name : String) : String =
    {
      var uri : String = baseUri

      if(name != "")
      {
        var text = name

        //Normalize text
        text = WikiUtil.removeWikiEmphasis(text)
        text = text.replace("&nbsp;", " ")//TODO decode all html entities here
        text = text.replace('(', ' ')
        text = text.replace(')', ' ')
        text = text.replace('\n', ' ')
        text = text.replace('\r', ' ')
        text = text.replace('\t', ' ')
        text = text.replace('\u0091', ' ')
        text = text.replace('\u0092', ' ')
        text = text.replaceAll("\\<.*?\\>", "") //strip tags
        text = WikiUtil.cleanSpace(text)
        if(text.length > 50) text = text.substring(0, 50)
        text = WikiUtil.wikiEncode(text)

        //Test if the base URI ends with a prefix of text
        var i = Math.max(baseUri.lastIndexOf('_'), baseUri.lastIndexOf('/')) + 1
        var done = false
        while(!done && i > 0 && baseUri.length - i < text.length)
        {
          if(baseUri.regionMatches(true, i, text, 0, baseUri.length - i))
          {
              text = text.substring(baseUri.length - i)
              done = true
          }

          i -= 1
        }

        //Remove leading underscore
        if(!text.isEmpty && text(0) == '_')
        {
          text = text.substring(1)
        }

        //Generate URI
        uri = baseUri + "__" + text
      }

      val index = uris.getOrElse(uri, 0) + 1
      uris = uris.updated(uri, index)
      uri + "__" + index.toString
    }

    private def nodeToString(node : Node, sb : mutable.StringBuilder)
    {
      node match
      {
        case TextNode(text, _, _) => sb.append(text)
        case null => //ignore
        case _ : TemplateNode => //ignore
        case _ : TableNode => //ignore
        case _ => node.children.foreach(child => nodeToString(child, sb))
      }
    }
}