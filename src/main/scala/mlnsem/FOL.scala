package trc1.mlnsem
import utcompling.scalalogic.fol.expression._
import utcompling.scalalogic.top.expression.Variable
import utcompling.mlnsemantics.modal._
import utcompling.scalalogic.discourse.candc.boxer.expression._
import utcompling.scalalogic.drt.expression.DrtExpression
import utcompling.scalalogic.discourse.candc.boxer.expression.interpreter.impl._
import utcompling.scalalogic.discourse.impl.BoxerDiscourseInterpreter
import utcompling.scalalogic.discourse.candc.parse.output.impl._
import utcompling.scalalogic.discourse.impl._
import utcompling.mlnsemantics.polarity._


/** A trait to convert boxer expressions to FOL*/
trait FOLConv {
  def toDrs(box: BoxerExpression) = {
    new Boxer2DrtExpressionInterpreter().interpret(box)
  }
  def clean(boxerEx: BoxerExpression) = {
    new OccurrenceMarkingBoxerExpressionInterpreterDecorator().interpret(
      new MergingBoxerExpressionInterpreterDecorator().interpret(
        new UnnecessarySubboxRemovingBoxerExpressionInterpreter().interpret(
          new PredicateCleaningBoxerExpressionInterpreterDecorator().interpret(boxerEx))))
  }

  def boxer2FOL(boxer:BoxerExpression):FolExpression ={
    val cleaned = clean(boxer)
    val drs = toDrs(cleaned)
    drs.fol
  }
}

@EnhanceStrings
class ProduceFOL extends FOLConv {
  val bdi = new MyBDI
  val mdi = new ModalDiscourseInterpreter(
    delegate = bdi,
    candcDiscourseParser = new CandcDiscourseParser(CandcImpl2.findBinary()),
    polarityLexicon = new PolarityLexicon(Map.empty[String, List[PolarityLexiconEntry]])
  )

  def extractOut(boxerOut:String):List[BoxerExpression] = {
    val drsDict = bdi.parseBoxerOutput(boxerOut)
    println("obtained #drsDict")
    drsDict.values.flatten.toList
  }

  def modalify(e:BoxerExpression):BoxerExpression = mdi.modalify(e)

  def toFOL(boxOut:String):List[FolExpression] = {
    extractOut(boxOut)
    .map(modalify(_))
    .map(boxer2FOL(_))
  }
}

/**
  * Takes an FolExpression and transforms it into Conjunction Normal Form.
  * Steps: 
  * -eliminate implications
  * -push negations to atom level
  * -skolemize
  * -drop universals
  * -distribute all disjunctions over conjunctions
  */
object ConvertToCNF extends ElimImplication with PushNegation with Skolemize with DropUniversal with DistributeOrOverAnd{
  def apply(fol:FolExpression):FolExpression = {
    //do not apply normalization - instead assume that at this point anything from 
    distributeOrOverAnd(dropUniversal(skolemize(pushNegation(elimImplication(fol)))))
  }
}

trait ElimImplication {
  def elimImplication(fol:FolExpression):FolExpression = fol match {
    //the crux.
    case FolIfExpression(first, second) => -elimImplication(first) | elimImplication(second)
    case FolIffExpression(first, second) => elimImplication(first -> second) | elimImplication(second -> first)
    //just keep searching
    case FolAllExpression(variable, term) => FolAllExpression(variable, elimImplication(term))
    case FolExistsExpression(variable, term) => FolExistsExpression(variable, elimImplication(term))
    case FolAndExpression(first, second) => elimImplication(first) & elimImplication(second)
    case FolEqualityExpression(first, second) => FolEqualityExpression(elimImplication(first), elimImplication(second))
    case FolOrExpression(first, second) => elimImplication(first) | elimImplication(second)
    case FolNegatedExpression(term) => -elimImplication(term)
    case FolVariableExpression(variable) => FolVariableExpression(variable)
    //pretty sure these are useless
    case FolLambdaExpression(variable, term) => FolLambdaExpression(variable, elimImplication(term))
    case FolApplicationExpression(func, arg) => FolApplicationExpression(elimImplication(func), elimImplication(arg))
  }
}

trait PushNegation {
  def findNegation(fol:FolExpression):FolExpression = fol match {
    case FolAllExpression(variable, term) => FolAllExpression(variable, findNegation(term))
    case FolExistsExpression(variable, term) => FolExistsExpression(variable, findNegation(term))
    //demorgan
    case FolAndExpression(first, second) => findNegation(first) & findNegation(second)
    case FolOrExpression(first, second) => findNegation(first) | findNegation(second)
    //???
    case FolEqualityExpression(first, second) => FolEqualityExpression(findNegation(first), findNegation(second))
    //start pushing down the negation
    case FolNegatedExpression(term) => moveNegation(term)
    //leave atoms alone
    case FolApplicationExpression(func, arg) => FolApplicationExpression(func, arg)
    case FolLambdaExpression(variable, term) => FolLambdaExpression(variable, term)
    case FolVariableExpression(variable) => FolVariableExpression(variable)
  }
  def moveNegation(fol:FolExpression):FolExpression = fol match {
    case FolAllExpression(variable, term) => FolExistsExpression(variable, moveNegation(term)) //all to exists
    case FolExistsExpression(variable, term) => FolAllExpression(variable, moveNegation(term)) //exists to all
    //demorgan
    case FolAndExpression(first, second) => moveNegation(first) | moveNegation(second) // and to or (by DeM)
    case FolOrExpression(first, second) => moveNegation(first) & moveNegation(second) //or to and (by DeM)
    //???
    case FolEqualityExpression(first, second) => FolEqualityExpression(moveNegation(first), moveNegation(second))
    //double negation elimination
    case FolNegatedExpression(term) => findNegation(term)
    //leave atoms alone
    case FolLambdaExpression(variable, term) => -FolLambdaExpression(variable, term)
    case FolApplicationExpression(func, arg) => -FolApplicationExpression(func, arg)
    case FolVariableExpression(variable) => -FolVariableExpression(variable)
  }

  def pushNegation(fol:FolExpression):FolExpression = findNegation(fol)
}

/** Applies skolemization to FOL expressions.
  * Assumptions: variable names standardized, implications removed, and negations pushed down.
  */
trait Skolemize {
  def skolemize(fol:FolExpression):FolExpression = skolemizeInner(fol, Nil)
    
  def skolemizeInner(fol:FolExpression, varList:List[Variable]):FolExpression = fol match {
    //ok here we need to start tracking the stuff
    case FolAllExpression(variable, term) => FolAllExpression(variable, skolemizeInner(term, variable::varList))
    case FolExistsExpression(variable, term) => skolemizeInner(term.replace(variable, constructFunc(variable, varList)), varList)
    //just keep looking
    case FolAndExpression(first, second) => skolemizeInner(first, varList) & skolemizeInner(second, varList)
    case FolOrExpression(first, second) => skolemizeInner(first, varList) | skolemizeInner(second, varList)
    //still not sure what to do about these. get rid of them?
    case FolEqualityExpression(first, second) => FolEqualityExpression(skolemizeInner(first, varList), skolemizeInner(second, varList))
    //leave anything here at the atomic level alone
    case e:FolLambdaExpression => e 
    case e:FolApplicationExpression => e
    case e:FolNegatedExpression => e //while generally we should search on, these will already be at bottom.
    case e:FolVariableExpression => e
  }

  def constructFunc(existVar:Variable, varList:List[Variable]):FolExpression = {
    (existVar::varList).map(FolVariableExpression(_).asInstanceOf[FolExpression])
    .reduceLeft {_ applyto _}
  }
}

/** drops universals. assumes everything above has been done
  *
  */
trait DropUniversal {
  def dropUniversal(fol:FolExpression):FolExpression = fol match {
    case FolAllExpression(variable, term) => dropUniversal(term)
    case FolAndExpression(first, second) => dropUniversal(first) & dropUniversal(second)
    case FolOrExpression(first, second) => dropUniversal(first) | dropUniversal(second)
    //still not sure what to do about these. get rid of them?
    case FolEqualityExpression(first, second) => FolEqualityExpression(dropUniversal(first), dropUniversal(second))
    //leave anything here at the atomic level alone
    case e:FolLambdaExpression => e 
    case e:FolApplicationExpression => e
    case e:FolNegatedExpression => e //while generally we should search on, these will already be at bottom.
    case e:FolVariableExpression => e
  }
}

trait DistributeOrOverAnd {
  def distributeOrOverAnd(fol:FolExpression):FolExpression = FolContainer(fol).consolidate.toCNF.toFOLE
}
