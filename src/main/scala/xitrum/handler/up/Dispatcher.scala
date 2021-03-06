package xitrum.handler.up

import java.lang.reflect.Method
import scala.collection.mutable.{ArrayBuffer, Map => MMap}

import io.netty.channel._
import io.netty.handler.codec.http._
import ChannelHandler.Sharable
import HttpResponseStatus._
import HttpVersion._

import xitrum.{Config, Controller, SkipCSRFCheck, Cache, Logger}
import xitrum.controller.Action
import xitrum.routing.Routes
import xitrum.exception.{InvalidAntiCSRFToken, MissingParam, SessionExpired}
import xitrum.handler.HandlerEnv
import xitrum.handler.down.ResponseCacher
import xitrum.handler.down.XSendFile
import xitrum.routing.{ControllerReflection, HttpMethodWebSocket}
import xitrum.scope.request.RequestEnv
import xitrum.scope.session.CSRF

object Dispatcher extends Logger {
  def dispatchWithFailsafe(actionMethod: Method, env: HandlerEnv) {
    val beginTimestamp = System.currentTimeMillis()
    var hit            = false

    val (controller, withActionMethod) = ControllerReflection.newControllerAndAction(actionMethod)
    controller(env)

    env.action     = withActionMethod
    env.controller = controller
    try {
      // Check for CSRF (CSRF has been checked if "postback" is true)
      if (controller.request.getMethod != HttpMethod.GET &&
          controller.request.getMethod != HttpMethodWebSocket &&
          !controller.isInstanceOf[SkipCSRFCheck] &&
          !CSRF.isValidToken(controller)) throw new InvalidAntiCSRFToken

      val cacheSeconds = withActionMethod.cacheSeconds

      if (cacheSeconds > 0) {             // Page cache
        tryCache(controller) {
          val passed = controller.callBeforeFilters()
          if (passed) runAroundAndAfterFilters(controller, withActionMethod)
        }
      } else {
        val passed = controller.callBeforeFilters()
        if (passed) {
          if (cacheSeconds == 0) {        // No cache
            runAroundAndAfterFilters(controller, withActionMethod)
          } else if (cacheSeconds < 0) {  // Action cache
            tryCache(controller) { runAroundAndAfterFilters(controller, withActionMethod) }
          }
        }
      }

      logAccess(controller, beginTimestamp, cacheSeconds, hit)
    } catch {
      case e =>
        // End timestamp
        val t2 = System.currentTimeMillis()

        // These exceptions are special cases:
        // We know that the exception is caused by the client (bad request)
        if (e.isInstanceOf[SessionExpired] || e.isInstanceOf[InvalidAntiCSRFToken] || e.isInstanceOf[MissingParam]) {
          logAccess(controller, beginTimestamp, 0, false)

          controller.response.setStatus(BAD_REQUEST)
          val msg = if (e.isInstanceOf[SessionExpired] || e.isInstanceOf[InvalidAntiCSRFToken]) {
            controller.resetSession()
            "Session expired. Please refresh your browser."
          } else if (e.isInstanceOf[MissingParam]) {
            val mp  = e.asInstanceOf[MissingParam]
            val key = mp.key
            "Missing param: " + key
          }
          if (controller.isAjax)
            controller.jsRespond("alert(" + controller.jsEscape(msg) + ")")
          else
            controller.respondText(msg)
        } else {
          logAccess(controller, beginTimestamp, 0, false, e)

          controller.response.setStatus(INTERNAL_SERVER_ERROR)
          if (Config.isProductionMode) {
            Routes.action500Method match {
              case None => respondDefault500AlertOrPage(controller)

              case Some(action500Method) =>
                if (action500Method == actionMethod) {
                  respondDefault500AlertOrPage(controller)
                } else {
                  controller.response.setStatus(INTERNAL_SERVER_ERROR)
                  dispatchWithFailsafe(action500Method, env)
                }
            }
          } else {
            val normalErrorMsg = e.toString + "\n\n" + e.getStackTraceString
            val errorMsg = if (e.isInstanceOf[org.fusesource.scalate.InvalidSyntaxException]) {
              val ise = e.asInstanceOf[org.fusesource.scalate.InvalidSyntaxException]
              val pos = ise.pos
              "Scalate syntax error: " + ise.source.uri + ", line " + pos.line + "\n" +
              pos.longString + "\n\n" +
              normalErrorMsg
            } else {
              normalErrorMsg
            }

            if (controller.isAjax)
              controller.jsRespond("alert(" + controller.jsEscape(errorMsg) + ")")
            else
              controller.respondText(errorMsg)
          }
        }
    }
  }

  //----------------------------------------------------------------------------

  private def respondDefault500AlertOrPage(controller: Controller) {
    if (controller.isAjax) {
      controller.jsRespond("alert(" + controller.jsEscape("Internal Server Error") + ")")
    } else {
      val response = new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR)
      XSendFile.set500Page(response)
      val env = controller.handlerEnv
      env.response = response
      env.channel.write(env)
    }
  }

  /** @return true if the cache was hit */
  private def tryCache(controller: Controller)(f: => Unit): Boolean = {
    ResponseCacher.getCachedResponse(controller) match {
      case None =>
        f
        false

      case Some(response) =>
        controller.channel.write(response)
        true
    }
  }

  private def runAroundAndAfterFilters(controller: Controller, action: Action) {
    controller.callAroundFilters(action)
    controller.callAfterFilters()
  }

  private def logAccess(controller: Controller, beginTimestamp: Long, cacheSecs: Int, hit: Boolean, e: Throwable = null) {
    def msgWithTime = {
      val endTimestamp = System.currentTimeMillis()
      val dt           = endTimestamp - beginTimestamp
      val env          = controller.handlerEnv

      (controller.request.getMethod) + " " + ControllerReflection.controllerActionName(controller.handlerEnv.action)                                                       +
      (if (env.uriParams.nonEmpty)        ", uriParams: "        + RequestEnv.inspectParamsWithFilter(env.uriParams       .asInstanceOf[MMap[String, List[Any]]]) else "") +
      (if (env.bodyParams.nonEmpty)       ", bodyParams: "       + RequestEnv.inspectParamsWithFilter(env.bodyParams      .asInstanceOf[MMap[String, List[Any]]]) else "") +
      (if (env.pathParams.nonEmpty)       ", pathParams: "       + RequestEnv.inspectParamsWithFilter(env.pathParams      .asInstanceOf[MMap[String, List[Any]]]) else "") +
      (if (env.fileUploadParams.nonEmpty) ", fileUploadParams: " + RequestEnv.inspectParamsWithFilter(env.fileUploadParams.asInstanceOf[MMap[String, List[Any]]]) else "") +
      ", " + dt + " [ms]"
    }

    def extraInfo = {
      if (cacheSecs == 0) {
        if (controller.isResponded) "" else " (async)"
      } else {
        if (hit) {
          if (cacheSecs < 0) " (action cache hit)"  else " (page cache hit)"
        } else {
          if (cacheSecs < 0) " (action cache miss)" else " (page cache miss)"
        }
      }
    }

    if (e == null) {
      if (logger.isDebugEnabled) logger.debug(msgWithTime + extraInfo)
    } else {
      if (logger.isErrorEnabled) logger.error("Dispatching error " + msgWithTime + extraInfo, e)
    }
  }
}

@Sharable
class Dispatcher extends SimpleChannelUpstreamHandler with BadClientSilencer {
  import Dispatcher._

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val m = e.getMessage
    if (!m.isInstanceOf[HandlerEnv]) {
      ctx.sendUpstream(e)
      return
    }

    val env        = m.asInstanceOf[HandlerEnv]
    val request    = env.request
    val pathInfo   = env.pathInfo
    val uriParams  = env.uriParams
    val bodyParams = env.bodyParams

    Routes.matchRoute(request.getMethod, pathInfo) match {
      case Some((actionMethod, pathParams)) =>
        env.pathParams = pathParams
        dispatchWithFailsafe(actionMethod, env)

      case None =>
        Routes.action404Method match {
          case None =>
            val response = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND)
            XSendFile.set404Page(response)
            env.response = response
            ctx.getChannel.write(env)

          case Some(actionMethod) =>
            env.pathParams = MMap.empty
            env.response.setStatus(NOT_FOUND)
            dispatchWithFailsafe(actionMethod, env)
        }
    }
  }
}
