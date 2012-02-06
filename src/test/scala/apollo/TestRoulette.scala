package apollo

trait Roulette extends Lang {

  abstract class RouletteRequest
  case class CashIn(amount:Int) extends RouletteRequest
  case object CashOut extends RouletteRequest
  case class Bet(amount:Int, n:Int) extends RouletteRequest
  abstract class RouletteResponse
  case object CashInFail extends RouletteResponse
  case object CashInSuccess extends RouletteResponse
  case class Win(newBalance:Int) extends RouletteResponse
  case class Lose(newBalance:Int) extends RouletteResponse
  type RouletteState = Int // amount on table
  def roulette(userId:Int):Body[Actor[RouletteRequest,RouletteResponse]] = 
	onMsg[RouletteRequest,RouletteResponse,RouletteState]((req,out,bal0) => req match {
		case CashIn(amt) => for (
			b<-withdraw(userId,amt);
			delta <- if (b) (out!CashInSuccess then result(amt)) else (out!CashInFail then result(0))
			) yield bal0 + delta
		case CashOut => for (
			_<-deposit(userId,bal0)
			) yield 0
		case Bet(amt,n) => for ( //TODO balance check
			s<-spin;
			newBalance <- if (s==n) (out!Win(bal0+36*amt) then result(bal0+36*amt)) else (out!Lose(bal0-amt) then result(bal0-amt))
			) yield newBalance
	}).actor(0); // initial cash = 0

  def spin:Body[Int] = for (n <- random) yield n % 37

   // stubs
  def withdraw(userId:Int,amount:Int):Body[Boolean] = 
  	debug("withdrawing "+amount+" for user "+userId) then result(true)
  def deposit(userId:Int,amount:Int):Body[Unit] = debug("depositing "+amount+" for user "+userId) 
  def random:Body[Int] = result(0) // Not the fairest roulette wheel ;-)

}

import org.scalatest._

class TestRouletteFunSuite extends FunSuite {
  object Roulette extends Evaluator with EmptyInitialEnvironment with Waiter with Roulette
  import Roulette._

  def testTrickMaven() = execute() 

  test("play roulette") {
	val b = Roulette.run(for(
		r <- roulette(1234);
		_<-r!CashIn(100);
		_<-r!Bet(10,10);
		_<-r!Bet(10,10);
		Lose(amt1)<-r!?Bet(10,10);
		Win(amt2)<-r!?Bet(10,0);
		_<-r!CashOut
	  ) yield (amt1==70)&&(amt2==430))
  	assert(b)
  }
}

