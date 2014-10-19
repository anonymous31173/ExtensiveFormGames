package edu.cmu.cs.kroer.extensive_form_game.abstraction;

import java.util.ArrayList;
import java.util.Arrays;

import cplex.CplexSolver;
import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearIntExpr;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import edu.cmu.cs.kroer.extensive_form_game.Game;
import edu.cmu.cs.kroer.extensive_form_game.GameGenerator;


/**
 * This die-roll poker class assumes that the Game instance contains a DRP game where each player rolls one private die, betting happens, and then they each roll another private die, followed by betting
 * A symmetric abstraction is computed, satisfying the constraints specified in Kroer & Sandholm 14: Extensive-Form Game Imperfect-Recall Abstractions with Bounds, such that an abstraction with bounded solution quality is found 
 * @author Christian Kroer
 *
 */
public class DieRollPokerAbstractor extends CplexSolver implements Abstractor {

	GameGenerator game;
	int numSides;
	int numAbstractionInformationSets;
	double[] sideProbabilities;
	
	

	
	// Indexed as [roll value][bucket]
	IloIntVar[][] firstRollAbstractionVariables;
	// Indexed as [first roll ][second roll][bucket]
	IloIntVar[][][] secondRollAbstractionVariables;
	// boolean variables designating whether bucket i is a bucket for private cards (bucketLevelSwitch[i] == 0) or public cards (bucketLevelSwitch[i] == 1) 
	IloIntVar[] bucketLevelSwitch;
	IloNumVar costVariablesSecondRoll[][];
	
	int[][] informationSetMapping;
	int[][][] actionMapping;
	
	public DieRollPokerAbstractor(GameGenerator game, int numSides, int numAbstractInformationSets) {
		super();
		this.game = game;
		this.numSides= numSides;
		this.numAbstractionInformationSets = numAbstractInformationSets;
		
		
		try {
			initializeDataStructures();
			//createBucketLevelSwitchVars();
			createSecondRollAbstractionVars();
			//createFirstRollAbstractionVars();
			//createFirstRollImplicationConstraints();
			addObjectiveCostSecondRoll();
			addCostsToObjective();
		} catch (IloException e) {
			e.printStackTrace();
		}
		
		sideProbabilities = new double[numSides];
		for (int side = 0; side < numSides; side++) {
			sideProbabilities[side] = 1.0 / numSides;
		}
		try {
			cplex.addMinimize(objective);
		} catch (IloException e) {
			e.printStackTrace();
		}
	}

	public DieRollPokerAbstractor(Game game, int numSides, int numAbstractInformationSets, double[] sideProbabilities) {
		this(game, numSides, numAbstractInformationSets);
		
		this.sideProbabilities = sideProbabilities;
	}
	

	public SignalAbstraction getAbstraction() {
		String[] signalNames = new String[numSides];
		for (int side = 1; side <= numSides; side++) {
			signalNames[side-1] = Integer.toString(side);
		}
		SignalAbstraction abstraction = new SignalAbstraction(signalNames);
		@SuppressWarnings("unchecked")
		ArrayList<ArrayList<Integer> >[] buckets = new ArrayList[numAbstractionInformationSets];
		// Construct a list containint the buckets, where each item in the bucket is a List of the integer indices of the signals (e.g.  {0,3} would mean rolling 1 followed by 4)
		for (int bucket = 0; bucket < numAbstractionInformationSets; bucket++) {
			buckets[bucket] = new ArrayList<ArrayList<Integer>>();
			for (int firstRoll = 1; firstRoll <= numSides; firstRoll++) {
			for (int secondRoll = 1; secondRoll <= numSides; secondRoll++) {
				try {
					if (cplex.getValue(secondRollAbstractionVariables[firstRoll][secondRoll][bucket]) > 1-cplexEpsilon) {
						ArrayList<Integer> signals = new ArrayList<Integer>(Arrays.asList(firstRoll-1, secondRoll-1));
						buckets[bucket].add(signals);
					}
				} catch (IloException e) {
					e.printStackTrace();
				}
			}}
		}	
		// We are going to map everything to the first item in the bucket. That means that we end up using the information set IDs belonging to items at index 0 in buckets.
		for (int bucket = 0; bucket < numAbstractionInformationSets; bucket++) {
			if (buckets[bucket].size() > 0) {
				for (int i = 0; i < buckets[bucket].size(); i++) {
					abstraction.addAbstraction(buckets[bucket].get(0), buckets[bucket].get(i));
				}
			}
		}
		// TODO
		return abstraction;
	}
	
	private void initializeDataStructures() throws IloException {
		firstRollAbstractionVariables = new IloIntVar[numSides+1][numAbstractionInformationSets];
		secondRollAbstractionVariables = new IloIntVar[numSides+1][numSides+1][numAbstractionInformationSets];
		costVariablesSecondRoll = new IloNumVar[numSides+1][numSides+1];
		for (int firstRoll = 1; firstRoll <= numSides; firstRoll++) {
		for (int secondRoll = 1; secondRoll <= numSides; secondRoll++) {
			costVariablesSecondRoll[firstRoll][secondRoll] = cplex.numVar(0, 2*game.getLargestPayoff(), "Cost("+firstRoll+";"+secondRoll+")");
		}}
		bucketLevelSwitch = new IloIntVar[numAbstractionInformationSets];
	}

	private void createBucketLevelSwitchVars() throws IloException {
		for (int bucket = 0; bucket < numAbstractionInformationSets; bucket++) {
			bucketLevelSwitch[bucket] = cplex.boolVar("S("+bucket+")");
		}
		
	}

	private void createSecondRollAbstractionVars() throws IloException {
		for (int firstRoll = 1; firstRoll <= numSides; firstRoll++) {
		for (int secondRoll = 1; secondRoll <= numSides; secondRoll++) {
			IloLinearNumExpr expr = cplex.linearNumExpr();
			for (int bucket = 0; bucket < numAbstractionInformationSets; bucket++) {
				secondRollAbstractionVariables[firstRoll][secondRoll][bucket] = cplex.boolVar("B("+firstRoll+";"+secondRoll+";"+bucket+")");
				// Ensure that the variable is only added to one bucket
				expr.addTerm(1, secondRollAbstractionVariables[firstRoll][secondRoll][bucket]);
				//addBucketLevelSwitchConstraint(secondRollAbstractionVariables[firstRoll][secondRoll][bucket], bucket, true);
			}
			cplex.addEq(expr, 1);
		}}
	}

	private void createFirstRollAbstractionVars() throws IloException {
		for (int side = 1; side <= numSides; side++) {
			for (int bucket = 0; bucket < numAbstractionInformationSets; bucket++) {
				firstRollAbstractionVariables[side][bucket] = cplex.boolVar("B("+side+";"+bucket+")");
				addBucketLevelSwitchConstraint(firstRollAbstractionVariables[side][bucket], bucket, true);
			}
		}
	}

		
	private void createFirstRollImplicationConstraints() throws IloException {
		for (int firstRoll1 = 1; firstRoll1 <= numSides; firstRoll1++) {
		for (int firstRoll2 = 1; firstRoll2 <= numSides; firstRoll2++) {
			IloLinearIntExpr expr = cplex.linearIntExpr();
			//expr.addTerm(arg0, arg1);
		}}
		
	}

	private void addObjectiveCostSecondRoll() throws IloException {
		for (int firstRoll1 = 1; firstRoll1 <= numSides; firstRoll1++) {
		for (int firstRoll2 = 1; firstRoll2 <= numSides; firstRoll2++) {
		for (int secondRoll1 = 1; secondRoll1 <= numSides; secondRoll1++) {
		for (int secondRoll2 = 1; secondRoll2 <= numSides; secondRoll2++) {
		for (int bucket = 0; bucket < numAbstractionInformationSets; bucket++) {
			double cost = computeCostOfAbstractingSecondRollPair(firstRoll1, firstRoll2, secondRoll1, secondRoll2);
			//objective.addTerm(cost, secondRollAbstractionVariables[firstRoll1][secondRoll1][bucket]);
			IloLinearNumExpr expr = cplex.linearNumExpr();
			expr.addTerm(cost, secondRollAbstractionVariables[firstRoll1][secondRoll1][bucket]);
			expr.addTerm(cost, secondRollAbstractionVariables[firstRoll2][secondRoll2][bucket]);
			expr.setConstant(-cost);
			cplex.addLe(expr, costVariablesSecondRoll[firstRoll1][secondRoll1]);
			cplex.addLe(expr, costVariablesSecondRoll[firstRoll2][secondRoll2]);
		}}}}}		
	}
	
	private double computeCostOfAbstractingSecondRollPair(int firstRoll1, int firstRoll2, int secondRoll1, int secondRoll2) {
		double sum1 = firstRoll1 + secondRoll1;
		double sum2 = firstRoll2 + secondRoll2;
		if (sum1 == sum2) { // if the information sets have the same sum, then abstracting them is lossless
			return 0;
		}
		
		double lower = Math.min(sum1, sum2);
		double higher = Math.max(sum1, sum2);		
		double error = 0;
		double probabilityOfSingleRoll = 1.0 / (numSides * numSides) ; 
		for (int opponentRoll1 = 1; opponentRoll1 <= numSides; opponentRoll1++) {
		for (int opponentRoll2 = 1; opponentRoll2 <= numSides; opponentRoll2++) {
			int sum = opponentRoll1 + opponentRoll2;
			// if the opponent hand draws with one and wins or loses against the other, we have a swing of largestPayoff, if it beats one and loses to the other, we have a swing of 2 * largestPayoff
			double offValue = (sum == lower || sum == higher ? 1 : 0) + (sum > lower && sum < higher ? 2: 0); 
			error += probabilityOfSingleRoll * offValue * game.getLargestPayoff(); 
		}}
		
		return error;
	}

	private void addCostsToObjective() throws IloException {
		for (int firstRoll = 1; firstRoll <= numSides; firstRoll++) {
		for (int secondRoll = 1; secondRoll <= numSides; secondRoll++) {
		for (int bucket = 0; bucket <= numAbstractionInformationSets; bucket++) {
			objective.addTerm(1, costVariablesSecondRoll[firstRoll][secondRoll]);
		}}}
	}
	
	private void addBucketLevelSwitchConstraint(IloIntVar var, int bucket, boolean isPublic) throws IloException {
		IloLinearIntExpr expr = cplex.linearIntExpr();
		if (isPublic) {
			expr.addTerm(2, var);
			expr.addTerm(-1, bucketLevelSwitch[bucket]);
		} else {
			expr.addTerm(1, var);
			expr.addTerm(1, bucketLevelSwitch[bucket]);
		}
		cplex.addLe(expr, 1);
	}
	
	@Override
	public int[][] informationSetMapping() {
		return informationSetMapping;
	}

	@Override
	public int[][][] actionMapping() {
		return actionMapping;
	}

}