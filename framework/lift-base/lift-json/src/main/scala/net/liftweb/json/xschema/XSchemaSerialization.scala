package net.liftweb.json.xschema {

object XSchemaSerialization {
  import _root_.net.liftweb.json.JsonAST._
  import _root_.net.liftweb.json.Validation._
  import XSchemaAST._
  
  private val TYPE        = "type"
  private val NAME        = "name"
  private val NAMESPACE   = "namespace"
  private val PROPERTIES  = "properties"
  private val VERSION     = "version"
  private val DEFINITIONS = "definitions"
  private val DEFAULT     = "default"
  private val TYPEPARAMS  = "typeParameters"
  private val COLLECTION  = "collection"
  private val SET         = "set"
  private val ARRAY       = "array"
  private val LIST        = "list"
  private val FIELDS      = "fields"
  private val TYPES       = "types"
  private val ORDER       = "order"

  def decompose(schema: XSchema): JValue = {
    val decomposers = List[PartialFunction[XSchema, JField]](
      { case x: Typed          => JField(TYPE,        JString(x.typename)) },
      { case x: Named          => JField(NAME,        JString(x.name)) },
      { case x: Default        => JField(DEFAULT,     x.defValue) },
      { case x: Parameterized  => JField(TYPEPARAMS,  JArray(x.typeParameters.map(decompose(_)))) },
      { case x: Ordered        => JField(ORDER,       JString(x.order.name)) },
      { case x: Properties     => JField(PROPERTIES,  JObject(x.properties.map { t => JField(t._1, JString(t._2)) }.toList)) },
      { case x: Namespaced     => JField(NAMESPACE,   JString(x.namespace.value)) },
      { case x: Versioned      => JField(VERSION,     JInt(x.version)) },
      { case x: XTuple         => JField(TYPES,       JArray(x.types.map(decompose(_)))) },
      { case x: XRoot          => JField(DEFINITIONS, JArray(x.definitions.map(decompose(_)))) },
      { case x: XCollection    => JField(COLLECTION,  JString(x.collection.name)) },
      { case x: XProduct       => JField(FIELDS,      JArray(x.fields.map(decompose(_)))) },
      { case x: XCoproduct     => JField(TYPES,       JArray(x.types.map(decompose(_)))) }
    )
    
    decomposers.foldLeft[JValue](JObject(Nil)) { (cur, d) => if (d.isDefinedAt(schema)) cur ++ d(schema) else cur }
  }
  
  def extract(json: JValue): XSchema = {
    def extract0(json: JPathValue): XSchema = {
      def extractArray(s: String): List[XSchema] = arrayFieldMap(s, json, extract0)
      
      def extractArrayOf[T <: XSchema](s: String, c: Class[T]): List[T] = extractArray(s).filter {
        x => c.isAssignableFrom(x.getClass) match {
          case true  => true
          case false => throw ValidationError("Expected elements in ${path} array to extract to " + c.toString() + ", but found: " + x.getClass, json ^ s)
        }
      }.map {
        x => x.asInstanceOf[T]
      }
    
      def typename = stringField(TYPE, json)
      
      def name = stringField(NAME, json)
      
      def defValue = json \ DEFAULT --> classOf[JObject]
      
      def typeParameters(n: Int): List[XReference] = {
        val array = extractArrayOf(TYPEPARAMS, classOf[XReference])
       
        if (array.length == n) array else throw ValidationError("Expected " + n + " type parameters, but found " + array.length, json ^ TYPEPARAMS) 
      }

      def order = stringField(ORDER, json) match { 
        case Ascending.name  => Ascending
        case Descending.name => Descending
        case Ignore.name     => Ignore
      }
      
      def properties = (json \? PROPERTIES).map { props => 
        Map(
          (props --> classOf[JObject]).values.map {
            case (k, JString(v)) => (k, v)
            case (k, v) => throw ValidationError("Expected string value but found: " + v, json ^ PROPERTIES)
          }.toList: _*
        )
      }.getOrElse(Map())
      
      def namespace = Namespace(stringField(NAMESPACE, json))
      
      def collection = stringField(COLLECTION, json) match {
        case XSet.name   => XSet
        case XArray.name => XArray
        case XList.name  => XList
      }
      
      def fields = extractArrayOf(FIELDS, classOf[XField])
      
      def types = extractArrayOf(TYPES, classOf[XReference])
      
      def definitions = extractArrayOf(DEFINITIONS, classOf[XDefinition])
      
      def version = integerField(VERSION, json).intValue
    
      typename match {
        case XCollection.typename => XCollection(typeParameters(1)(0), collection)
        case XMap.typename        => XMap(typeParameters(2)(0), typeParameters(2)(1))
        case XOptional.typename   => XOptional(typeParameters(1)(0))
        case XTuple.typename      => XTuple(types)
        case XRealField.typename  => XRealField(typeParameters(1)(0), name, properties, defValue, order)
        case XViewField.typename  => XViewField(typeParameters(1)(0), name, properties)
        case XProduct.typename    => XProduct(namespace, name, properties, fields)
        case XCoproduct.typename  => XCoproduct(namespace, name, properties, types)
        case XConstant.typename   => XConstant(namespace, name, properties, typeParameters(1)(0), defValue)
        case XRoot.typename       => XRoot(version, definitions, properties)
        
        case _ => XReference(typename)
      }
    }
    
    extract0(!json)
  }
}

}