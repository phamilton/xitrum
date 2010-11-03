package xt.framework

import xt._
import xt.middleware.Env

import scala.collection.JavaConversions
import scala.collection.mutable.{Map => MMap}

import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

trait Helper extends Logger {
  // These variables will be set by middleware Failsafe or
  // when an action renders a view, or when a view renders another view

  var remoteIp: String            = _
  var channel:  Channel           = _
  var request:  HttpRequest       = _
  var response: HttpResponse      = _
  var env:      Env               = _

  // Equivalent to @xxx variables of Rails
  protected var atMap: MMap[String, Any] = _

  /**
   * Sets references from another helper. Not cloning because we want for example
   * if something is added in this atMap, it will be reflected at other's atMap.
   */
  def setRefs(other: Helper) {
    setRefs(other.remoteIp, other.channel, other.request, other.response, other.env, other.atMap)
  }

  def setRefs(remoteIp:  String,
              channel:   Channel,
              request:   HttpRequest,
              response:  HttpResponse,
              env:       Env,
              atMap:     MMap[String, Any]) {
    this.remoteIp  = remoteIp
    this.channel   = channel
    this.request   = request
    this.response  = response
    this.env       = env
    this.atMap     = atMap
  }

  //----------------------------------------------------------------------------

  /**
   * Returns a singular element.
   */
  def param(key: String): String = {
  	val m = env.params
    if (m.containsKey(key))
      m.get(key).get(0)
    else
      throw new xt.middleware.Failsafe.MissingParam(key)
  }

  def paramo(key: String): Option[String] = {
    val values = env.params.get(key)
    if (values == null) None else Some(values.get(0))
  }

  /**
   * Returns a list of elements.
   */
  def params(key: String): List[String] = {
  	val m = env.params
    if (m.containsKey(key))
      JavaConversions.asBuffer[String](m.get(key)).toList
    else
      throw new xt.middleware.Failsafe.MissingParam(key)
  }

  def paramso(key: String): Option[List[String]] = {
    val values = env.params.get(key)
    if (values == null) None else Some(JavaConversions.asBuffer[String](values).toList)
  }

  //----------------------------------------------------------------------------

  def at(key: String, value: Any) = atMap.put(key, value)
  def at[T](key: String): T       = atMap(key).asInstanceOf[T]

  //----------------------------------------------------------------------------

  /**
   * Renders a view without layout.
   *
   * csasOrAs: String in the pattern "Articles#index" or "index"
   */
  def render(csasOrAs: String) = Scalate.render(csasOrAs, this)
}
