package xitrum

import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

import com.hazelcast.core.{IMap, MapEntry}
import com.hazelcast.query.Predicate

import xitrum.controller.Action
import xitrum.routing.ControllerReflection

object Cache extends Logger {
  val cache = Config.hazelcastInstance.getMap("xitrum/cache").asInstanceOf[IMap[String, Any]]

  def remove(key: Any) {
    cache.removeAsync(key.toString)
  }

  def removeAction(action: Action) {
    val keyPrefix = pageActionPrefix(action)
    removePrefix(keyPrefix)
  }

  def pageActionPrefix(controller: Controller): String = {
    val action = controller.handlerEnv.action
    pageActionPrefix(action)
  }

  private def pageActionPrefix(action: Action): String =
    "xitrum/page-action/" + ControllerReflection.controllerActionName(action)

  private def removePrefix(keyPrefix: Any) {
    val keyPrefixS = keyPrefix.toString
    val prefixPredicate = new Predicate[String, Any] {
      def apply(mapEntry: MapEntry[String, Any]) = mapEntry.getKey.startsWith(keyPrefixS)
    }

    val keys = cache.keySet(prefixPredicate)
    val it = keys.iterator
    while (it.hasNext) {
      val key = it.next
      cache.removeAsync(key)
    }
  }

  //---------------------------------------------------------------------------

  def put(key: Any, value: Any) {
    logger.debug("put: " + key)
    cache.putAsync(key.toString, value)
  }

  def putSecond(key: Any, value: Any, seconds: Int) {
    logger.debug("put (" + seconds + "s): " + key)
    cache.put(key.toString, value, seconds, TimeUnit.SECONDS)
  }
  def putMinute(key: Any, value: Any, minutes: Int) { putSecond(key, value, minutes * 60) }
  def putHour  (key: Any, value: Any, hours:   Int) { putMinute(key, value, hours   * 60) }
  def putDay   (key: Any, value: Any, days:    Int) { putHour  (key, value, days    * 24) }

  def putIfAbsent(key: Any, value: Any) {
    logger.debug("putIfAbsent: " + key)
    cache.putIfAbsent(key.toString, value)
  }

  def putIfAbsentSecond(key: Any, value: Any, seconds: Int) {
    logger.debug("putIfAbsent (" + seconds + "s): " + key)
    cache.putIfAbsent(key.toString, value, seconds, TimeUnit.SECONDS)
  }
  def putIfAbsentMinute(key: Any, value: Any, minutes: Int) { putIfAbsentSecond(key, value, minutes * 60) }
  def putIfAbsentHour  (key: Any, value: Any, hours:   Int) { putIfAbsentMinute(key, value, hours   * 60) }
  def putIfAbsentDay   (key: Any, value: Any, days:    Int) { putIfAbsentHour  (key, value, days    * 24) }

  //---------------------------------------------------------------------------

  /**
   * Gets data from cache with type cast.
   * Application version up etc. may cause cache restoring to be failed.
   * In this case, we remove the cache.
   */
  def getAs[T](key: Any): Option[T] = {
    if (!Config.isProductionMode) return None

    try {
      val value = cache.get(key)
      if (value != null) Some(value.asInstanceOf[T]) else None
    } catch {
      case _ =>
        logger.warn("Cache data restoring failed, will now remove it, key: {}", key)
        cache.remove(key)
        None
    }
  }

  def tryCacheSecond[T](key: Any, secs: Int)(f: => T): T = {
    getAs[T](key) match {
      case Some(t) => t

      case None =>
        val value = f
        putIfAbsentSecond(key, value, secs)
        value
    }
  }

  def tryCacheMinute[T](key: String, minutes: Int)(f: => T): T = tryCacheSecond(key, minutes * 60)(f)
  def tryCacheHour[T]  (key: String, hours:   Int)(f: => T): T = tryCacheMinute(key, hours   * 60)(f)
  def tryCacheDay[T]   (key: String, days:    Int)(f: => T): T = tryCacheHour  (key, days    * 24)(f)
}
