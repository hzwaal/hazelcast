package com.mendix.hazelcast

import java.time.LocalDateTime
import java.util
import java.util.concurrent.TimeUnit
import java.util.logging.{ Level, Logger }
import com.hazelcast.cluster.{ Member, MembershipEvent, MembershipListener }
import com.hazelcast.core.Hazelcast

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class Demo {
  import Demo._

  private val hazelcast = Hazelcast.newHazelcastInstance
  private val channel = hazelcast.getTopic[String]("channel")
  private val sessions = hazelcast.getMap[String, Session]("sessions")
  private val locks = hazelcast.getMap[String, String]("locks")
  private var shutdown = false

  hazelcast.getCluster.addMembershipListener(new MembershipListener {
    override def memberAdded(event: MembershipEvent): Unit =
      prompt(s"Node ${event.getMember.getAddress} joined the cluster")
    override def memberRemoved(event: MembershipEvent): Unit =
      prompt(s"Node ${event.getMember.getAddress} left the cluster")
  })

  channel.addMessageListener { message =>
    if (message.getPublishingMember != hazelcast.getCluster.getLocalMember)
      prompt(s"Node ${message.getPublishingMember.getAddress} sent: '${message.getMessageObject}'")
  }

  def setLogLevel(level: String): String = {
    Logger.getLogger("com.hazelcast").setLevel(Level.parse(level.toUpperCase)) // TODO: why doesn't this work?
    ok
  }

  def listNodes(): String = {
    def location(member: Member): String = locationString(local = member.localMember)
    hazelcast.getCluster.getMembers.asScala.map(m => s"${location(m)} ${m.getAddress}").mkString("\n")
  }

  def broadcast(message: String): String = {
    channel.publish(message)
    ok
  }

  def login(user: String): String = {
    val now = LocalDateTime.now
    Option(sessions.putIfAbsent(user, Session(now, user, now))) match {
      case Some(s) => s"User already logged in at: ${s.start.format(timeFormat)}"
      case None => ok
    }
  }

  def login(user: String, ttl: String): String = {
    val now = LocalDateTime.now
    sessions.putIfAbsent(user, Session(now, user, now), ttl.toLong, TimeUnit.SECONDS)
    ok
  }

  def setActive(user: String): String = {
    val now = LocalDateTime.now

    def updateActive(entry: util.Map.Entry[String, Session]): String =
      Option(entry.getValue) match {
        case Some(session) =>
          entry.setValue(session.copy(lastActive = now))
          ok
        case None => s"Unknown user: ${entry.getKey}"
      }

    val future = sessions.submitToKey(user, updateActive).toFuture
    Await.result(future, 1.second)
  }

  def listSessions(): String = {
    def location(user: String): String = locationString(local = sessions.localKeySet.contains(user))
    sessions.values.asScala.map(s => s"${location(s.user)} $s").mkString("\n")
  }

  def logout(user: String): String =
    Option(sessions.remove(user)) match {
      case Some(_) => ok
      case None => s"Unknown user: $user"
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
      locks.unlock(key)
      ok
    }

  def listLocks(): String = {
    def location(key: String): String = locationString(local = locks.localKeySet.contains(key))
    locks.asScala.collect { case (key, user) if locks.isLocked(key) => s"${location(key)} $key ($user)" }.mkString("\n")
  }

  def increment(counter: String): String = {
    val value = hazelcast.getCPSubsystem.getAtomicLong(counter).incrementAndGet
    value.toString
  }

  def exit(): String = {
    hazelcast.shutdown()
    shutdown = true
    "Bye..."
  }

  def isRunning: Boolean = !shutdown
}

object Demo {
  private val ok = "OK"

  private def prompt(message: String = ok): Unit = {
    Console.print(s"$message\ndemo> ")
    Console.flush()
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
      Command("broadcast", "message")(demo.broadcast),
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
      Command("timed", "flag")(setTimed),
      Command("exit")(demo.exit),
      Command("help")(() => listCommands)
    )

    def listCommands: String =
      commands.sortBy(_.keyword).map(c => s"- $c").mkString("\n")

    prompt()
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