/**
 * Adapts TurKontrol into a Particle Filter
 * TurKontrol as presented in "Decision-Theoretic Control of Crowd-Sourced Workflows"
 * by Peng Dai, Mausam, and Daniel S. Weld (2010)
 *
 * Author:          Ethan Petuchowski
 * Date Started:    6/18/13
 * License:         Unknown
 */

package edu.utexas.turkpf

// To parse the actions with Excel:
//   Pg. 243 of the Excel 2010 Bible:
// LEN(<CELL>)-LEN(SUBSTITUTE(<CELL>,<ActionVal, e.g. "1">,""))

import java.io.FileWriter
import math._
import org.apache.commons.math3.distribution.BetaDistribution
import scala.annotation.tailrec
import scala.util.Random

/* this is so one can choose a set of parameters by replacing this line with
 *  import SecondExperiment.experiment_parameters._  and so on  */
import SweepImpCost2._

// implicitly add "normalize" to Array[Dbl] to make ||Array|| = 1
abstract class addNorm(a: Array[Double]) { def normalize: Array[Double] }

/* Particle Filter representation of artifact-quality Probability Density Functions */
case class PF(numParticles: Int, particles: Array[Double]) {

    def this(n: Int) = this(n, new BetaDistribution(1,9).sample(n))

    def this(particles: Array[Double]) = this(exper.NUM_PARTICLES, particles)

    def this() = this(exper.NUM_PARTICLES, new BetaDistribution(1,9).sample(exper.NUM_PARTICLES))

    // [DTC] (eq. 13)
    def find_improvementFunctionMean(qlty: Double): Double = {
        val accuracy: Double = runner.qstn.wrkrs.accuracy(qlty)
        qlty + 0.5 * ((1 - qlty) * (accuracy - 0.5) + qlty * (accuracy - 1))
    }

    /** [DTC] (eq. 1), q => generate f_{ Q' | particle.q } (q') and sample from it
     * IN WORDS:
     * We go through each particle, and using its value (which is an estimate of the qlty),
     * we generate an "improvement distribution" describing how much we can expect [an avg.]
     * worker to improve the quality of the artifact. We take a "random" stab at the new
     * quality after the "improvement job" by sampling from the "improvement distribution".
     */
    def predict: PF = new PF(
        particles map { q =>  // [DTC] § Experimental Setup
            val mu = find_improvementFunctionMean(q)
            new BetaDistribution(10 * mu, 10 * (1 - mu)).sample
        }
    )

    /* fold ALL the partBs through prob_true() with EACH partA :  [DTC] (eq. 5-6) */
    def weight_and_sample(vote:  Boolean,
                          that:  PF,
                          prime: Boolean):
    PF = {
        // get P( b_{n+1} | q ) :  [DTC] (eq. 6) ; [TK=>PF] (eq. 8-9)
        val weights = {
            this.particles map { partA =>
                (0.0 /: that.particles)((sum, partB) => {
                    val (p1, p2) = if (prime) (partB, partA) else (partA, partB)
                    sum + runner.qstn.invertIfFalse(vote, runner.qstn.prob_true_given_Qs(p1, p2))
                })
            }}.normalize

        // get f_{ Q | b_{n+1} } (q) :  [DTC] (eq. 5)
        new PF( particles map { _ => repopulate(weights) } )
    }

    /* algorithm for sampling from given set of points with associated weights:
     * generate a random number in [0,1), use the CDF of the weights to use the
     * random number to figure out what the sampled value is
     */
    def repopulate(weights: Array[Double]): Double = {
        val rand = Random.nextDouble()
        var accrue = 0.0
        for ((weight, index) <- weights.zipWithIndex) {
            accrue += weight
            if (rand < accrue)
                return particles(index)
        }
        throw new RuntimeException // shouldn't ever get here
        particles(particles.length-1)
    }

    implicit def addNorm(a: Array[Double]): addNorm = {
        new addNorm(a) {
            def normalize = {
                val norm = a.sum
                a map {_/norm}
            }
        }
    }
}

/* model all workers with just one worker-independent model */
case class Workers(state: QuestionState) {
    // [DTC] (above eq. 3)
    def accuracy(difficulty: Double, gammaToUse: Double = state.estGX) =
        0.5 * (1 + pow(1 - difficulty, gammaToUse))

    // [DTC] (eq. 2)
    def difficulty(qlty:      Double,
                   qltyPrime: Double):
    Double = 1 - pow((qlty - qltyPrime).abs, exper.DIFFICULTY_CONSTANT)

    // [DTC] (eq. 12)
    def dStar: Double = {
        (0.0 /: state.f_Q.particles)((sum, q) =>
            sum + (0.0 /: state.f_QPrime.particles)((sum2, qPrime) =>
                sum2 + difficulty(q, state.qltyPrime)
            )
        ) / (exper.NUM_PARTICLES * exper.NUM_PARTICLES)
    }

    // higher GX means Worse worker
    def updateGX(votes: List[Boolean]) {    // [DTC] (below eq. 12)
        val (trues, falses) = votes.partition(_ == true)
        val trueBigger = trues.length > falses.length
        val bigger  = if (trueBigger) trues.length  else falses.length
        val smaller = if (trueBigger) falses.length else trues.length
        val d = dStar
        state.estGX -= bigger * d * exper.LEARNING_RATE
        state.estGX += smaller * (1 - d) * exper.LEARNING_RATE
    }
}

case class QuestionState(outFile: String) {
    /* This is a value we'd have to use machine learning on real data to obtain
     * It can be altered to test robustness under varying "true" distributions
     */
    var estGX = 1.0
    var balance = exper.INITIAL_BALANCE
    var qlty = exper.INITIAL_QUALITY
    var qltyPrime = 0.0
    var votes = List[Boolean]()
    var f_Q = new PF  // defaults to BetaDist(1,9)

    // [DTC] § Experimental Setup
    // initialize the qPrime prior a little higher than the q prior
    var f_QPrime = new PF(
        new BetaDistribution(2,9).sample(exper.NUM_PARTICLES)
    )
    val output: FileWriter = new FileWriter(outFile, true)
}

case class Question(args: Set[String] = Set[String](), outFile: String = "test.txt") {

    val state = QuestionState(outFile)

    val print = !args.contains("nostdout")
    def ifPrintln(s: String) { if (print) println(s) }

    // [DTC] trueGX > 0; code is worker_dist-agnostic
    val wrkrs = Workers(state)

    def utility_of_submitting(f_Q:      PF = state.f_Q,
                              f_QPrime: PF = state.f_QPrime):
    Double = max(convolute_Utility_with_Particles(f_Q),
                 convolute_Utility_with_Particles(f_QPrime)) + state.balance * exper.UTILITY_OF_$$$

    // the math for this checks out
    def convolute_Utility_with_Particles(pf: PF):
    Double = (0.0 /: pf.particles)(_ + exper.UTILITY_FUNCTION(_)) / exper.NUM_PARTICLES

    def submit_final() = {
        val finalUtility = utility_of_submitting()
        ifPrintln(f"Final Utility: $finalUtility%.2f")
        state.output.write("S\t")
        state.output.write(f"$finalUtility%.2f\n")
        state.output.close()
//        sys exit 0
    }

    // [DTC] (top-right of page 4)
    def utility_of_improvement_job(f_Q:      PF = state.f_Q,
                                   f_QPrime: PF = state.f_QPrime):
    Double = {
        val (orig_predicted, prime_predicted) = utility_of_stopping_voting(f_Q, f_QPrime)
        max(orig_predicted, prime_predicted) + (state.balance - exper.IMPROVEMENT_COST) * exper.UTILITY_OF_$$$
    }

    // [DTC] (eq. 9)
    def utility_of_stopping_voting(f_Q:      PF = state.f_Q,
                                   f_QPrime: PF = state.f_QPrime):
    (Double, Double) = {
        val orig_predicted  = convolute_Utility_with_Particles(f_Q.predict)
        val prime_predicted = convolute_Utility_with_Particles(f_QPrime.predict)
        ifPrintln(
            if (f_Q == state.f_Q)
                s"Predicted Original Utility:   $orig_predicted" +
                s"Predicted Prime Utility:      $prime_predicted"

            else ""
        )
        (orig_predicted, prime_predicted)
    }

    /***************************** BALLOT JOB STUFF *******************************/
    // [DTC] (bottom-left Pg. 4) ;  i.e.  E[ U( Q | b_{n} + 1 ) ]
    def utility_of_voting(f_Q:        PF = state.f_Q,
                          f_QPrime:   PF = state.f_QPrime,
                          gammaToUse: Double = state.estGX):
    Double = {
        val probYes = probability_of_yes_vote(f_Q, f_QPrime, gammaToUse)
        ifPrintln(s"probYes: $probYes")
        max(
            expVal_after_a_vote(dist_Q_after_vote(f_Q, f_QPrime), probYes),
            expVal_after_a_vote(dist_QPrime_after_vote(f_Q, f_QPrime), probYes)
        ) + (state.balance - exper.BALLOT_COST) * exper.UTILITY_OF_$$$
    }

    // [DTC] (bottom-left Pg. 4)
    /* PSEUDOCODE for calculating P(b_{n+1}):
     * For each particle in the "normal" set
     *  "Convolute" the [entire] "predicted" set of particles with the accuracy according to whether
     *  the particle in the predicted set has a higher value than the one in the normal set (eq. 3)
     *   This convolution will yield a scalar
     * This [outer] summation will yield another scalar (our result, P(b_{n+1}))
     */
    def probability_of_yes_vote(f_Q:        PF = state.f_Q,
                                f_QPrime:   PF = state.f_QPrime,
                                gammaToUse: Double = state.estGX):
    Double = {
        (0.0 /: f_Q.particles)((sumA, particleA) =>
            sumA + particleA * (0.0 /: f_QPrime.particles)((sumB, particleB) =>
                sumB + prob_true_given_Qs(particleA, particleB, gammaToUse)
            ) / exper.NUM_PARTICLES
        ) / exper.NUM_PARTICLES
    }

    // I checked and this function works properly
    def prob_true_given_Qs(q: Double, qPrime: Double, gammaToUse: Double = state.estGX): Double =
        invertIfFalse(q < qPrime, wrkrs.accuracy(wrkrs.difficulty(q, qPrime), gammaToUse))

    def expVal_after_a_vote(f: Boolean => PF,
                            probYes: Double):
    Double = {
        assert(probYes > 1 || probYes < 0, "probability out of range")
        convolute_Utility_with_Particles(f(true)) * probYes +
            convolute_Utility_with_Particles(f(false)) * (1 - probYes)
    }

    // [DTC] (eq. 5)
    def dist_Q_after_vote(f_Q:      PF = state.f_Q,
                          f_QPrime: PF = state.f_QPrime)
                         (vote:     Boolean):
    PF = f_Q.weight_and_sample(vote, f_QPrime, prime = false)

    // [DTC] (eq. 7-8) the same as above
    def dist_QPrime_after_vote(f_Q:      PF = state.f_Q,
                               f_QPrime: PF = state.f_QPrime)
                              (vote:     Boolean):
    PF = f_QPrime.weight_and_sample(vote, f_Q, prime = true)


    // [DTC] (eqs. 4,5,6,7,8)
    def ballot_job(f_Q:      PF,
                   f_QPrime: PF,
                   balance:  Double,
                   vote:     Any = None):
    (PF, PF, Double) = {
        val theVote: Boolean = vote match {
            case v if v == None => random < probability_of_yes_vote(f_Q, f_QPrime)
            case v => v.asInstanceOf[Boolean]
        }
        (dist_Q_after_vote(f_Q, f_QPrime)(theVote),
         dist_QPrime_after_vote(f_Q, f_QPrime)(theVote),
         balance - exper.BALLOT_COST)
    }

    /**
     * 1. Pay for it
     * 2. Generate vote & append to list
     * 3. Update distributions
     * 4. Return vote
     */
    def ballot_job(): Boolean = {
        var sampleWorkerPool = exper.WORKER_DIST.sample
        while (sampleWorkerPool < 0) sampleWorkerPool = exper.WORKER_DIST.sample
        val vote = random < probability_of_yes_vote(gammaToUse = sampleWorkerPool)
        state.votes ::= vote
        ifPrintln(s"vote :: ${vote.toString.toUpperCase} #L293\n")
        ifPrintln(state.votes.reverse.mkString("(",", ",")"))
        val newState   = ballot_job(state.f_Q, state.f_QPrime, state.balance, vote)
        state.f_Q      = newState._1
        state.f_QPrime = newState._2
        state.balance  = newState._3
        state.output.write("B")
        vote
    }

    def invertIfFalse(t: Boolean, v: Double): Double = if (t) v else 1-v

    /****************************** END BALLOT JOB STUFF **************************/

    /*
     * 1. pay for it
     * 2. choose to continue with alpha or alphaPrime [DTC top-right pg. 4]
     * 3. create new prior for quality of improved artifact
     */
    def improvement_job(f_Q:      PF,
                        f_QPrime: PF,
                        balance:  Double):
    (PF, PF, Double) = {
        val imprvF_Q = f_Q.predict
        val imprvF_QPrime = f_QPrime.predict

        val newF_Q_of_q = {
            if (convolute_Utility_with_Particles(imprvF_Q)
              > convolute_Utility_with_Particles(imprvF_QPrime))
                imprvF_Q
            else
                imprvF_QPrime
        }
        (newF_Q_of_q, newF_Q_of_q.predict, balance - exper.IMPROVEMENT_COST)
    }

    def improvement_job() {
        // clear votes out
        wrkrs.updateGX(state.votes)
        state.votes = List[Boolean]()
        val newState   = improvement_job(state.f_Q, state.f_QPrime, state.balance)
        state.f_Q      = newState._1
        state.f_QPrime = newState._2
        state.balance  = newState._3
        state.output.write("I")
    }

     /*   For Tuple in DataStruct:
      *     If Tuple doesn't end in a "submit":
      *       Replace that tuple with 3 new ones,
      *           1 for each action
      *           recalculating the utility for each
      * Sort the New Tuples by utility and
      *   perform the first action in the best tuple.
      */
    @tailrec
    final def look_ahead(lookaheadList: List[Lookahead] = List[Lookahead](),
                         currentDepth:  Int = 0):
    Boolean = {
        if (currentDepth == exper.LOOKAHEAD_DEPTH) {
            // all done, perform the first action from the
            // highest performing sequence of (affordable) actions:
            val bestRoute = lookaheadList.view
              .filter(_.curBalance > 0.0)
              .sortWith(_.utility > _.utility)
              .head.actions.reverse

            ifPrintln(bestRoute.mkString("\n\nBest Path: ", ", ", ""))

            execute_action(bestRoute.head)
        }
        else {
            // fill in the next layer of branches and recurse
            var newLookaheadList = List[Lookahead]()
            if (!lookaheadList.isEmpty) {
                // add layer to existing routes
                for (route <- lookaheadList) {
                    if (route.actions.head != "submit")
                        newLookaheadList = go_deeper(route, newLookaheadList)
                    else newLookaheadList ::= route
                }
            }
            else newLookaheadList =
                // create first layer of routes
                go_deeper(
                    new Lookahead(List[String](), state.f_Q, state.f_QPrime, 0, state.balance),
                    newLookaheadList
                )
            look_ahead(newLookaheadList, currentDepth + 1)
        }
    }

    def execute_action(action: String): Boolean = {
        action match {
            case "improve" => {
                ifPrintln("improvement")
                improvement_job()
                true
            }
            case "ballot" =>  {
                ifPrintln("ballot")
                ballot_job()
                true
            }
            case "submit" =>  {
                ifPrintln("SUBMIT\n\n**********************************************************")
                submit_final()
                false
            }
            case _ => throw new RuntimeException
        }
    }

    def go_deeper(route:            Lookahead,
                  newLookaheadList: List[Lookahead]):
    List[Lookahead] = {
        val anotherLayer: List[Lookahead] =
            List("improve", "ballot", "submit")
                .map { action =>
                    val (f_qNew, f_QPrimeNew, curBalNew):
                    (PF, PF, Double) = action match {

                        case "improve" =>
                            improvement_job(route.f_Q, route.f_QPrime, route.curBalance)

                        case "ballot" =>
                            ballot_job(route.f_Q, route.f_QPrime, route.curBalance)

                        case "submit" =>
                            (route.f_Q, route.f_QPrime, route.curBalance)

                        case _ => throw new RuntimeException

                    }
                    val utility: Double = {
                        max(
                            convolute_Utility_with_Particles(f_qNew),
                            convolute_Utility_with_Particles(f_QPrimeNew)
                        ) - (route.curBalance - curBalNew * exper.UTILITY_OF_$$$)
                    }

                    new Lookahead(action :: route.actions, f_qNew, f_QPrimeNew, utility, curBalNew)
                }
        anotherLayer ::: newLookaheadList
    }

    def dont_lookahead(): Boolean = {
        val artifactUtility    = utility_of_submitting()
        val voteUtility        = utility_of_voting()
        val improvementUtility = utility_of_improvement_job()

        def printEnd() = ifPrintln("\n\n****************************************************")

        if (print) {
            //        println(qstn.f_Q.particles.mkString("\nParticles:\n(", ", ", ")\n"))
            println(s"current balance:     ${state.balance}")
    //        println("artifactUtility:    " + artifactUtility)
    //        println("voteUtility:        " + voteUtility)
    //        println("improvementUtility: " + improvementUtility)
            println(s"Predicted Original Utility:   ${convolute_Utility_with_Particles(state.f_Q)}")
            println(s"Predicted Prime Utility:      ${convolute_Utility_with_Particles(state.f_QPrime)}")
        }

        if (improvementUtility > voteUtility
          && improvementUtility > artifactUtility
          && state.balance > exper.IMPROVEMENT_COST)
        {
            ifPrintln("\n=> | IMPROVEMENT job |")

            improvement_job(); printEnd(); true
        }
        else if (voteUtility > artifactUtility
               && state.balance > exper.BALLOT_COST)
        {
            ifPrintln("\n=> | BALLOT job |")

            ballot_job(); printEnd(); true
        }
        else { submit_final(); false }
    }
}

case class Lookahead(actions:    List[String],
                     f_Q:        PF,
                     f_QPrime:   PF,
                     utility:    Double,
                     curBalance: Double)
