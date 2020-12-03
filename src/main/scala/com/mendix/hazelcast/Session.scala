package com.mendix.hazelcast

import java.time.LocalDateTime

case class Session(start: LocalDateTime, user: String, lastActive: LocalDateTime) extends Serializable {
  override def toString: String = s"${start.format(timeFormat)} / ${lastActive.format(timeFormat)} - $user"
}
