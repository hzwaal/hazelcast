package com.mendix.hazelcast

private object Command {
  def apply(keyword: String)(action: () => String): Command =
    new Command(keyword)({ case _ => action() })

  def apply(keyword: String, par: String)(action: String => String): Command =
    new Command(keyword, par)({ case List(arg) => action(arg) })

  def apply(keyword: String, par1: String, par2: String)(action: (String, String) => String): Command =
    new Command(keyword, par1, par2)({ case List(arg1, arg2) => action(arg1, arg2) })
}

case class Command private (keyword: String, parameters: String*)(action: PartialFunction[List[String], String]) {
  private val regex = (keyword :: List.fill(parameters.size)("([A-Za-z0-9]+)")).mkString(" ").r

  def execute(line: String): Option[String] = regex.unapplySeq(line).map(action)

  override def toString: String = s"$keyword ${parameters.map(p => s"<$p>").mkString(" ")}"
}
