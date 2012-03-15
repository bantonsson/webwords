package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import scala.io.Source
import akka.actor._
import java.net.URL
import akka.util.Timeout
import akka.testkit.{ImplicitSender, TestKit}
import akka.testkit.TestActor.AutoPilot

class IndexerActorSpec extends TestKit(ActorSystem("IndexerActorSpec")) with FlatSpec with ShouldMatchers
    with BeforeAndAfterAll with ImplicitSender {

    behavior of "splitWords"

    override def afterAll = { system.shutdown() }

    it should "split one word" in {
        val split = IndexerActor.splitWords("foo").toList
        split should be(List("foo"))
    }

    it should "split two words" in {
        val split = IndexerActor.splitWords("foo bar").toList
        split should be(List("foo", "bar"))
    }

    it should "split words with multiline text" in {
        val split = IndexerActor.splitWords("foo bar\nbaz boo\nhello world\n").toList
        split should be(List("foo", "bar", "baz", "boo", "hello", "world"))
    }

    it should "split a realistic sentence with punctuation" in {
        val split = IndexerActor.splitWords("Hello, this is (some sort of) sentence.").toList
        split should be(List("Hello", "this", "is", "some", "sort", "of", "sentence"))
    }

    behavior of "wordCount"

    private def wordCount(s: String) = {
        IndexerActor.wordCount(IndexerActor.splitWords(s))
    }

    it should "count one word" in {
        val counts = wordCount("foo")
        counts should be(Map("foo" -> 1))
    }

    it should "count a couple words" in {
        val counts = wordCount("foo bar foo")
        counts.get("foo") should be(Some(2))
        counts.get("bar") should be(Some(1))
        counts should have size 2
    }

    behavior of "mergeCounts"

    it should "merge nonintersecting counts" in {
        val result = IndexerActor.mergeCounts(Map("a" -> 3), Map("b" -> 4))
        result should be(Map("a" -> 3, "b" -> 4))
    }

    it should "merge intersecting counts" in {
        val result = IndexerActor.mergeCounts(Map("a" -> 3, "c" -> 7), Map("b" -> 4, "c" -> 5))
        result should be(Map("a" -> 3, "b" -> 4, "c" -> 12))
    }

    behavior of "IndexerActor"

    private def load(resource: String) = {
        // this is sort of goofy because it splits into lines then unsplits,
        // in a real program you'd probably use Apache commons IOUtils
        // or something. it's just a unit test hack.
        val source = Source.fromInputStream(getClass.getResource(resource).openStream(), "UTF-8")
        val sb = new StringBuilder()
        source.getLines() foreach { l => sb.append(l); sb.append("\n") }
        sb.toString
    }

    private val wordsInSamples = Map(
        "Functional_programming.html" -> Seq(("programming", 82), ("functional", 61), ("http", 49), ("languages", 45),
            ("functions", 40), ("Functional", 36), ("Programming", 34), ("function", 33), ("Retrieved", 32),
            ("2009", 31), ("language", 27), ("com", 25), ("imperative", 25), ("html", 24), ("08", 23),
            ("evaluation", 23), ("www", 21), ("used", 20), ("oriented", 19), ("10", 18), ("edit", 18),
            ("order", 18), ("Haskell", 18), ("use", 18), ("org", 17), ("program", 16), ("based", 15),
            ("first", 15), ("pure", 14), ("programs", 14), ("example", 14), ("2006", 14), ("research", 13),
            ("class", 13), ("side", 12), ("strict", 12), ("recursion", 12), ("list", 12), ("effects", 11),
            ("higher", 11), ("data", 11), ("2011", 11), ("2008", 11), ("logic", 11), ("type", 11), ("29", 11),
            ("other", 11), ("2007", 10), ("Language", 10), ("contrast", 10)))

    implicit val timeout = Timeout(system.settings.config.getMilliseconds("akka.timeout.test"))

    it should "index some sample HTML" in {
        val html = load("Functional_programming.html")
        val indexer = system.actorOf(Props[IndexerActor])
        within(timeout.duration) {
            indexer ! IndexHtml(new URL("http://en.wikipedia.org/wiki/"), html)
            expectMsgType[IndexedHtml] match {
                case IndexedHtml(index) =>
                    index.links.size should be(593)
                    index.wordCounts.toSeq should be(wordsInSamples("Functional_programming.html"))
            }
        }
        system.stop(indexer)
    }

    it should "index a lot of HTML concurrently" in {
        val html = load("Functional_programming.html")
        val indexer = system.actorOf(Props[IndexerActor])
        val numMsgs = 20
        within(timeout.duration) {
            // check all replies that we get
            setAutoPilot(new AutoPilot {
                var numProcessedMsgs = 0
                def run(sender: ActorRef, msg: Any): Option[AutoPilot] = {
                    msg match {
                        case IndexedHtml(index) =>
                            index.links.size should be(593)
                            index.wordCounts.toSeq should be(wordsInSamples("Functional_programming.html"))
                        case whatever =>
                            throw new IllegalStateException("Unexpected reply to url fetch: " + whatever)
                    }
                    numProcessedMsgs = numProcessedMsgs + 1
                    if (numProcessedMsgs < numMsgs)
                        Option(this)
                    else
                        None
                }
            })
            // send all messages
            for (i <- 1 to numMsgs)
                indexer ! IndexHtml(new URL("http://en.wikipedia.org/wiki/"), html)
            // wait for all replies
            receiveN(numMsgs)
        }
        system.stop(indexer)
    }
}
