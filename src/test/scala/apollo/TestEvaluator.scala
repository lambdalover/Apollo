package apollo

import org.scalatest._
import scala.concurrent.ops._

class TestEvaluator {
  object MyEvaluator extends Evaluator with EmptyInitialEnvironment with Waiter
  import MyEvaluator._

  def intPrint(prefix:String):StrF[Stream[Int],Unit] = 
	doEach { case n => {println(prefix+" "+n); nothing} }
  def pairPrint(prefix:String):StrF[Stream[(Int,Int)],Unit] = 
	doEach { case (n1,n2) => {println(prefix+" "+n1+","+n2); nothing} }

  def intProducer(sink:Sink[Int], inp:List[(Int,Int)]):Body[Unit] = for (
		env <- getEnv;
		_ <- evalThread({
			      spawn {
				for((millis,n)<-inp) {
					Thread.sleep(millis)
					run(sink!n)
				}
			      }
			      nothing
		      })
	  ) yield ()

  def remember[A](as:Stream[A]):Body[Ref[List[A]]] = for (
			seen <- newRef(List[A]());
			_ <- onMsg[A,Unit,Unit]((a,_,_) => 
					seen!(Update(a::_))
				).strf(()) -< as
		) yield seen
  def sum(is:Stream[Int]):Body[Ref[Int]] = for (
			seen <- newRef(0);
			_ <- onMsg[Int,Unit,Unit]((i,_,_) => 
					seen!(Update(_+i))
				).strf(()) -< is
		) yield seen

  def double:StrF[Stream[Int],Stream[Int]] = onMsg[Int,Int,Unit]((n,out,_)=>out!(n*2)).strf(())
  def doubleIntStream:Boolean = run(for(
		(sink,is) <- pipe[Int];
		ds <- double -< is;
		ris <- remember(is);
		dis <- remember(ds);
  		_ <- intProducer(sink, List((5,1),(5,2)));
		_<-waitFor(50);
		Val(ival) <- ris!?Read();
		Val(dval) <- dis!?Read();
		val ok = (ival.equals(List(2,1)) && dval.equals(List(4,2)))
  	) yield ok)
  def double2 = run(for(
		(sink,is) <- pipe[Int];
		(d1,d2) <- (double &&& (double >>> double)) -< is;
		d1s <- remember(d1);
		d2s <- remember(d2);
  		is <- intProducer(sink, List((5,1),(5,2)));
		_<-waitFor(40);
		Val(d1val) <- d1s!?Read();
		Val(d2val) <- d2s!?Read();
		val ok = (d1val.equals(List(4,2)) && d2val.equals(List(8,4)))
  ) yield ok)

  	// TODO test cases isn't quite right (doesn't exercise the bug)
  def acReply:Unit = run(for(
		ac1 <- onMsg[Int,Int,Unit]((_,out,_)=>out!2).actor();
		ac2 <- onMsg[Unit,Unit,Unit]((_,out,_)=>ac1!?1 then nothing).actor()
  	) yield ())

  def split(splitval:Int):StrF[Stream[Int],(Stream[Int],Stream[Int])] = 
  	onMsg[Int,Int,Unit]((n,out,_) => if (n > splitval) out!n else nothing).strf(()) &&& 
	onMsg[Int,Int,Unit]((n,out,_) => if (n <= splitval) out!n else nothing).strf(()) 
  
  def splitter[A](splitf:A=>Boolean):StrF[Stream[A],(Stream[A],Stream[A])] = strf((in:Stream[A]) => for(
  		(sink1,a1) <- pipe[A];
  		(sink2,a2) <- pipe[A];
		_<- onMsg[A,A,Unit]((a,out,_) => if (splitf(a)) sink1!a else sink2!a).strf(()) -< in
  	) yield (a1,a2)
  )
    
  def refEquals[A](r:Ref[A], a:A):Body[Boolean] = for (
  		Val(ra) <- r!?Read()
  	) yield ra.equals(a)
  def testSplitter:Boolean = run(for(
		(sink,is) <- pipe[Int];
		(big,little) <- (double >>> splitter((_:Int)>10)) -< is;
		bigs <- remember(big);
		littles <- remember(little);
  		_ <- intProducer(sink, List((1000,1),(1,11),(1,2),(2,12),(1,6)));
		_ <- waitFor(1200); 
		Val(b )<- bigs!?Read();
		Val(l) <- littles!?Read()
  	) yield (b.equals(List(12,24,22)) && l.equals(List(4,2))))
  def testImmediateSending:Boolean = run(for(
		(sink,a) <- pipe[Int];
		b <- (double >>> double >>> double) -< a;
		c <- remember(b);
  		_ <- intProducer(sink, List((0,1)));
		  // wait a moment for the message to be sent
		_ <- waitFor(1);
		Val(res) <- c!?Read()
  	) yield  res.equals(List(1)))
  def testAtomic:Boolean = run(for(
		(sink,a) <- pipe[Int];
  		_ <- intProducer(sink, List((0,1),(1,1),(1,3),(50,4),(18,5),(2,6),(1,7),(0,8)));
		(res1,res2) <- atomic(testAtomicHelper) -< a;
		  // Now wait a while to let some messages flow through...
		_ <- waitFor(200); 
		Val(r1val) <- res1!?Read();
		Val(r2val) <- res2!?Read();
		val expected = List(16,14,12,10,8) // each instance of 'double' should miss the first 3 messages
  	) yield  r1val.equals(expected) && r2val.equals(expected))
  def testAtomicHelper:StrF[Stream[Int],(Ref[List[Int]],Ref[List[Int]])]= 
  	(a:Stream[Int]) =>for(
		b1 <- double -< a;
		c1 <- remember(b1);
			// wait for 10 millis before constructing the second function,
			// thus missing the first 3 messages so we can verify each function still gets the same
		_ <- waitFor(10); 
		b2 <- double -< a;
		c2 <- remember(b2)
  	) yield  (c1,c2)
  def testEnv:Boolean = 
  	run(for(_<-put("foo", 1234); Some(n)<-get("foo")) yield(n.equals(1234)))
  def testRefs:Boolean =
	run(for(
	  bref <- newRef[Boolean](true);
	  Val(bval) <- bref!?Read()
	) yield bval)
  def testResult:Boolean =
  	run(result(true))
  def testFlatMap:Boolean =
  	run(result(false).flatMap[Boolean](_=>result(true)))
  def testBasic:Boolean =
  	run(for(t1<-result(true); t2 <- result(true)) yield t1&&t2)
  def testAcState:Boolean =
  	run(for(
		ac<-onMsg[Unit,Int,Int]((_,out,cur) => out!(cur+1) then result(cur+1)).actor(0);
		_<-ac!();
		_<-ac!();
		_<-ac!();
		n<-ac!?()
	) yield n==4)
  def followedBy[A](first:A=>Boolean, last:(A,A)=>Boolean, term:(A,A)=>Boolean):StrF[Stream[A],Stream[(A,A)]] = 
  	(as:Stream[A]) => for (
		(sink,stream) <- pipe[(A,A)];
		_ <- onMsg[A,Unit,Unit]( (a,_,_) => 
			if (first(a))
				lookFor[A](last(a,_), term(a,_),a,sink,as)
			else 
				nothing	
			).strf(()) -< as
	) yield stream
  def lookFor[A](last:A=>Boolean, term:A=>Boolean, prev:A, sink:Sink[(A,A)], as:Stream[A]):Body[Unit] =
	onMsg[A,Unit,Boolean]( (a,_,alive) => 
		if (alive && last(a)) // really not a clever way of doing this...
			sink!(prev,a) then result(true)
		else if (term(a))
			result(false)
		else
			result(alive)
	).strf(true) -< as 	then nothing
  def testFollowedBy:Boolean = run(for(
		(sink,stream) <- pipe[Int];
		fb <- followedBy[Int](_>0,_>_,_+_>100) -< stream;
		r <- remember(fb);
  		_ <- intProducer(sink, List((0,0),(0,1),(30,0),(0,2)));
		  // wait a moment for the message to be sent
		_ <- waitFor(100);
		Val(res) <- r!?Read()
  	) yield  res.equals(List((1,0)))
  )
  def testImplicit:Boolean = run(((result[Int](_)) >>> ((n:Int) => result(n>0)))-<(1))
}

class TestEvaluatorFunSuite extends FunSuite {
  def testTrickMaven() = execute() 

  val evaluator = new TestEvaluator
  test("composition of strfs")(pending)
  test("producer and consumer") (assert(evaluator.doubleIntStream == true))
  test("&&& >>>") (assert(evaluator.double2 == true))
  //test("state monad") (evaluator.stateMonad)
  test("ignoring reply") (evaluator.acReply)
  test("combinators") (assert(evaluator.testSplitter == true))
  test("atomic construction") (assert(evaluator.testAtomic == true))
  test("environment put/get") (assert(evaluator.testEnv == true))
  test("refs") (assert(evaluator.testRefs == true))
  test("result") (assert(evaluator.testResult == true))
  test("flatmap") (assert(evaluator.testFlatMap == true))
  test("basic Body[_]") (assert(evaluator.testBasic == true))
  test("basic actor state") (assert(evaluator.testAcState == true))
  test("basic pattern recognition") (assert(evaluator.testFollowedBy == true))
  test("implicit strf") (assert(evaluator.testImplicit == true))
}


