package com.mendix.hazelcast

private object Command {
  def apply(keyword: String)(action: () => String): Command =
    new Command(keyword)({ case _ => action() })

  def apply[A: FromString](keyword: String, par: String)(action: A => String): Command =
    new Command(keyword, par)({ case List(arg) => action(fromString(arg)) })

  def apply[A: FromString, B: FromString](keyword: String, par1: String, par2: String)(action: (A, B) => String): Command =
    new Command(keyword, par1, par2)({ case List(arg1, arg2) => action(fromString(arg1), fromString(arg2)) })

  private def fromString[A](string: String)(implicit fromString: FromString[A]): A = fromString.toA(string)

  class FromString[A](val toA: String => A)
  object FromString {
    implicit val stringFromString = new FromString(identity)
    implicit val intFromString = new FromString(_.toInt)
    implicit val longFromString = new FromString(_.toLong)
  }
}

case class Command private (keyword: String, parameters: String*)(action: PartialFunction[List[String], String]) {
  private val regex = (keyword :: List.fill(parameters.size)("([A-Za-z0-9]+)")).mkString(" ").r

  def execute(line: String): Option[String] = regex.unapplySeq(line).map(action)

  override def toString: String = s"$keyword ${parameters.map(p => s"<$p>").mkString(" ")}"
}
