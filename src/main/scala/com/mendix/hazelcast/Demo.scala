package com.mendix.hazelcast

import com.hazelcast.Scala._
import com.hazelcast.config.Config
import com.hazelcast.core.{ Hazelcast, IAtomicLong, Member }
import com.hazelcast.query.TruePredicate

import java.time.LocalDateTime
import java.util.logging.{ Level, Logger }
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class Demo {
  import Demo._

  Demo.setLogLevel(Level.SEVERE)

  private val hazelcast = {
    val config = new Config
    config.getCPSubsystemConfig.setCPMemberCount(3)
    Hazelcast.newHazelcastInstance(config)
  }
  private val channel = hazelcast.getTopic[Execute]("channel")
  private val sessions = hazelcast.getMap[String, Session]("sessions")
  private val locks = hazelcast.getMap[String, String]("locks")
  private var shutdown = false

  hazelcast.getCluster.onMemberChange() {
    case MemberAdded(member, _) => prompt(s"Node ${member.getAddress} joined the cluster")
    case MemberRemoved(member, _) => prompt(s"Node ${member.getAddress} left the cluster")
  }

  channel.onMessage() { message =>
    val execute = message.getMessageObject
    val sender = message.getPublishingMember
    if (execute.onSender || (sender != hazelcast.getCluster.getLocalMember)) {
      execute.action()
      prompt(s"${execute.description} on request of ${sender.getAddress}'")
    }
  }

  def setLogLevel(level: String): String = {
    val logLevel = Level.parse(level.toUpperCase)
    channel.publish(Execute(s"Changed log level to '$level'", onSender = true, () => Demo.setLogLevel(logLevel)))
    ok
  }

  def listNodes(): String = {
    def location(member: Member): String = locationString(local = member.localMember)
    hazelcast.getCluster.getMembers.asScala.map(m => s"${location(m)} ${m.getAddress}").mkString("\n")
  }

  def send(message: String): String = {
    channel.publish(Execute(s"Received from $this", onSender = false, () => prompt(s"Message: '$message'")))
    ok
  }

  def login(user: String): String = {
    val now = LocalDateTime.now
    sessions.putIfAbsent(user, Session(now, user, now), Duration.Inf) match {
      case Some(s) => s"User already logged in at: ${s.start.format(timeFormat)}"
      case None => ok
    }
  }

  def login(user: String, ttl: String): String = {
    val now = LocalDateTime.now
    sessions.putIfAbsent(user, Session(now, user, now), ttl.toInt.seconds)
    ok
  }

  def setActive(user: String): String = {
    val now = LocalDateTime.now
    val updated = sessions.async.update(user)(_.copy(lastActive = now))
    if (Await.result(updated, 1.second)) ok else s"Unknown user: $user"
  }

  def listSessions(): String = {
    def location(user: String): String = locationString(local = sessions.localKeySet.contains(user))
    val result = sessions.query(new TruePredicate)(_.toString)
    result.map { case (user, string) => s"${location(user)} $string" }.mkString("\n")
  }

  def logout(user: String): String = {
    val removed = sessions.async.remove(user)
    Await.result(removed, 1.second) match {
      case Some(_) => ok
      case None => s"Unknown user: $user"
    }
  }

  // alternatively use hazelcast.getCPSubsystem.getLock(key).lock()
  def lock(key: String, user: String): String = {
    locks.lock(key)
    locks.put(key, user)
    ok
  }

  def unlock(key: String): String =
    if (!locks.isLocked(key)) s"Unknown lock: $key"
    else if (!locks.tryLock(key)) "Locked by other node"
    else {
      locks.remove(key)
      locks.unlock(key) // for tryLock
      locks.unlock(key) // for original lock
      ok
    }

  def listLocks(): String = {
    def location(key: String): String = locationString(local = locks.localKeySet.contains(key))
    locks.asScala.collect { case (key, user) if locks.isLocked(key) => s"${location(key)} $key ($user)" }.mkString("\n")
  }

  def increment(counter: String): String = {
    val value = getCounter(counter).incrementAndGet
    value.toString
  }

  def getValue(counter: String): String = {
    val value = getCounter(counter).get
    value.toString
  }

  def exit(): String = {
    hazelcast.shutdown()
    shutdown = true
    "Bye..."
  }

  def isRunning: Boolean = !shutdown

  override def toString: String = hazelcast.getCluster.getLocalMember.getAddress.toString

  private def getCounter(counter: String): IAtomicLong = hazelcast.getCPSubsystem.getAtomicLong(counter)
}

object Demo {
  private val ok = "OK"

  private case class Execute(description: String, onSender: Boolean, action: () => Unit)

  private def prompt(message: String = ok): Unit = {
    Console.print(s"$message\ndemo> ")
    Console.flush()
  }

  private def setLogLevel(level: Level): Unit = {
    Logger.getLogger("com.hazelcast").setLevel(level)
    Logger.getLogger("").getHandlers.foreach(_.setLevel(level))
  }

  def main(args: Array[String]): Unit = {
    Console.println("Connecting...")
    val demo = new Demo
    var timed = false

    def setTimed(flag: String): String = {
      timed = Set("true", "on", "yes", "1").contains(flag.toLowerCase)
      ok
    }

    lazy val commands = Seq(
      Command("nodes")(demo.listNodes),
      Command("send", "message")(demo.send),
      Command("log", "level")(demo.setLogLevel),
      Command("login", "user")(demo.login),
      Command("login", "user", "ttl")(demo.login),
      Command("logout", "user")(demo.logout),
      Command("active", "user")(demo.setActive),
      Command("sessions")(demo.listSessions),
      Command("lock", "key", "user")(demo.lock),
      Command("unlock", "key")(demo.unlock),
      Command("locks")(demo.listLocks),
      Command("increment", "counter")(demo.increment),
      Command("get", "counter")(demo.getValue),
      Command("timed", "flag")(setTimed),
      Command("exit")(demo.exit),
      Command("help")(() => listCommands)
    )

    def listCommands: String =
      commands.sortBy(_.keyword).map(c => s"- $c").mkString("\n")

    prompt(s"This nodes is $demo")
    while (demo.isRunning) {
      try {
        val line = Console.in.readLine().trim
        val response = if (line.isEmpty) ""
        else {
          val before = System.currentTimeMillis
          val response = commands.view.flatMap(_.execute(line)).headOption
          val elapsed = System.currentTimeMillis - before
          if (timed) Console.println(s"($elapsed ms)")
          response.getOrElse("Syntax error")
        }
        prompt(response)
      } catch {
        case NonFatal(e) =>
          prompt(s"OOPS: ${e.getMessage}}")
      }
    }
  }
}