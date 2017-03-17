package extensive_form_game_solver;

import java.util.Arrays;
import java.util.HashMap;

import extensive_form_game.Game;
import extensive_form_game.Game.Action;
import extensive_form_game.Game.Node;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import gurobi.*;

public class LimitedLookAheadOpponentSolver extends SequenceFormLPSolver {
	
	double[] nodeEvaluationTable;
	int lookAhead;
	
	GRBVar[] sequenceDeactivationVars; // Variables denoting whether a given sequence is deactivated
	GRBVar[] sequenceLookAheadVars;  // Variables denoting the choice of descendant sequences at depth i + k, for some non-deactivated sequence at depth i
	GRBVar[] informationSetValueVars; // Variables denoting the maximum utility achieved at an information set for the limited look ahead player
	
	GRBLinExpr[] evaluationSum; // indexed as [dualSequenceId]. [dualSequenceId] contains a weighted sum over primal sequences that lead to to nodes k levels below the dual sequence, where the weights are the evaluation of the node(s) times the chance probability.
	
	double[] dualUpperBounds; // TODO: compute these, diagonal matrix M for deactivating dual constraints
	double[] primalUpperBounds; // TODO: compute these, diagonal matrix M for deactivating primal incentive constraints
	double maxPayoff;
	double minPayoff;
	double [] maxEvaluationValueForSequence; // [sequenceId] returns the maximum value in the nodeEvaluationTable at depth k over all trees rooted at the sequence
	double epsilon; // This controls how much we want an action to be heuristically preferred before we consider it "incentivized"
	
	

	
	public LimitedLookAheadOpponentSolver(Game game, int playerToSolveFor, double[] nodeEvaluationTable, int lookAhead, double epsilon) {
		super(game, playerToSolveFor);
		this.nodeEvaluationTable = nodeEvaluationTable;
		makeNodeEvaluationTableNonNegative();
		this.lookAhead = lookAhead;
		this.epsilon = epsilon;
		try {
			setUpModel();
		} catch (GRBException e) {
			System.out.println("LimitedLookAheadOpponentSolver error, setUpModel() exception");
			e.printStackTrace();
		}
	}
	
	public LimitedLookAheadOpponentSolver(Game game, int playerToSolveFor, double[] nodeEvaluationTable, int lookAhead) {
		this(game, playerToSolveFor, nodeEvaluationTable, lookAhead, 0.001);
	}
	
	private void makeNodeEvaluationTableNonNegative() {
		double minValue = 0;
		for (int i = 0; i < nodeEvaluationTable.length; i++) {
			if (nodeEvaluationTable[i] < minValue) minValue = nodeEvaluationTable[i];
		}
		for (int i = 0; i < nodeEvaluationTable.length; i++) {
			nodeEvaluationTable[i] += Math.abs(minValue);
		}
	}
	
	private void setUpModel() throws GRBException {
		initializeDataStructures();
		
		computeAuxiliaryInformationForNodes();
		
		createInformationSetValueVars();
		createBooleanDualVars();
		model.update();
		createInformationSetValueConstraints();
		computeMaxMinPayoff(game.getRoot());
		computeMaxEvaluation();
		
		addDualConstraintRemoval();
		addPrimalIncentiveConstraints();
		model.addConstr(sequenceDeactivationVars[0], '=', 0, "");
		CreateDeactivationSequenceFormConstraints(game.getRoot(), sequenceDeactivationVars[0], new TIntHashSet());
	}
	
	private void initializeDataStructures() {
		maxPayoff = -Double.MAX_VALUE;
		minPayoff = Double.MAX_VALUE;
		//sequenceDeactivationVars = new GRBVar[getNumDualSequences()];
		//sequenceLookAheadVars = new GRBVar[getNumDualSequences()];
		dualUpperBounds = new double[getNumDualSequences()];
		maxEvaluationValueForSequence = new double[getNumDualSequences()];
		for (int dualSequence = 0; dualSequence < getNumDualSequences(); dualSequence++) maxEvaluationValueForSequence[dualSequence] = -Double.MAX_VALUE;
	}
	
	public void printSequenceActivationValues() throws GRBException {
		for (int i = 1; i < numDualSequences; i++) {
			System.out.println("D" + dualSequenceNames[i] + " = " + sequenceDeactivationVars[i].get(GRB.DoubleAttr.X));
		}
	}
	
	private void createInformationSetValueVars() throws GRBException {
		String[] names = new String[numDualInformationSets];
		for (int i = 0; i < numDualInformationSets; i++) names[i] = "V" + i;
		double[] lb = new double[numDualInformationSets];
		char[] types = new char[numDualInformationSets];
		Arrays.fill(lb, -Double.MAX_VALUE);
		Arrays.fill(types, GRB.CONTINUOUS);
		informationSetValueVars = model.addVars(lb, null, null, types, names);
	}
	
	private void createInformationSetValueConstraints() {
		createInformationSetValueConstraintsRecursive(game.getRoot(), new TIntHashSet(), new TIntArrayList(), 0);
	}
	
	private void createInformationSetValueConstraintsRecursive(int currentNodeId, TIntSet visited, TIntList dualParentSequences, int primalParentSequence) {
		Node node = game.getNodeById(currentNodeId);
		if (node.isLeaf()) return;
		
		for (Action action : node.getActions()) {
			if (node.getPlayer() == playerNotToSolveFor && !visited.contains(node.getInformationSet())) {
				int newSequenceId = getSequenceIdForPlayerNotToSolveFor(node.getInformationSet(), action.getName());
				dualParentSequences.add(newSequenceId);
				createInformationSetValueConstraintsRecursive(action.getChildId(), visited, dualParentSequences, primalParentSequence);
				dualParentSequences.removeAt(dualParentSequences.size()-1);
			} else if (node.getPlayer() == playerToSolveFor){
				createInformationSetValueConstraintsRecursive(action.getChildId(), visited, dualParentSequences, getSequenceIdForPlayerToSolveFor(node.getInformationSet(), action.getName()));
			}			
		}		
		if (node.getPlayer() == playerNotToSolveFor) {
			visited.add(node.getInformationSet());
		}
	}
	
	private void createBooleanDualVars() throws GRBException {
		String[] namesDeactivationVars = new String[numDualSequences];
		String[] namesLookAheadVars = new String[numDualSequences];
		for (int i = 0; i < numDualSequences; i++) {
			namesDeactivationVars[i] = "D" + dualSequenceNames[i];
			namesLookAheadVars[i] = "T" + dualSequenceNames[i];
		}
		double[] lb = new double[numDualSequences];
		char[] types = new char[numDualSequences];
		Arrays.fill(lb, 0);
		Arrays.fill(types, GRB.BINARY);
		sequenceDeactivationVars = model.addVars(lb, null, null, types, namesDeactivationVars);
		sequenceLookAheadVars = model.addVars(lb, null, null, types, namesLookAheadVars);
	}
	
	private void CreateDeactivationSequenceFormConstraints(int currentNodeId, GRBVar parentSequence, TIntSet visited) throws GRBException{
		Node node = game.getNodeById(currentNodeId);
		if (node.isLeaf()) return;
		
		if (node.getPlayer() == playerNotToSolveFor && !visited.contains(node.getInformationSet())) {
			visited.add(node.getInformationSet());
			GRBLinExpr sum = new GRBLinExpr();
			//sum.addTerm(-1, parentSequence);
			for (Action action : node.getActions()) {
				// real-valued variable in (0,1)
				int sequenceId = getSequenceIdForPlayerNotToSolveFor(node.getInformationSet(), action.getName());
				GRBVar v = sequenceDeactivationVars[sequenceId];
				// add 1*v to the sum over all the sequences at the information set
				sum.addTerm(1, v);
				CreateDeactivationSequenceFormConstraints(action.getChildId(), v, visited);
				model.addConstr(v, GRB.GREATER_EQUAL, parentSequence, "");
			}
			// if parent sequence is deactived, it subtracts one from the sum
			sum.addTerm(-1, parentSequence);
			// if parent sequence is NOT deactivated, this requires at least one action to not be deactivated
			model.addConstr(sum, GRB.LESS_EQUAL, node.getActions().length - 1, "");
		} else {
			for (Action action : node.getActions()) {
				if (node.getPlayer() == playerNotToSolveFor) {
					// update parentSequence to be the current sequence
					int sequenceId = getSequenceIdForPlayerNotToSolveFor(node.getInformationSet(), action.getName());
					GRBVar v = sequenceDeactivationVars[sequenceId];
					CreateDeactivationSequenceFormConstraints(action.getChildId(), v, visited);
				} else {
					CreateDeactivationSequenceFormConstraints(action.getChildId(), parentSequence, visited);
				}
			}
		}
	}

	
	private void addDualConstraintRemoval() throws GRBException {
		// Start at 1, we do not want to deactivate the empty sequence
		for (int sequenceId = 1; sequenceId < numDualSequences; sequenceId++) {
			// expr represents the term (-maxPayoff * sequenceDeactivationsVars[sequenceId])
//			GRBLinExpr expr = new GRBLinExpr();
			double biggestDifferential = maxPayoff - minPayoff;
//			expr.addTerm(biggestDifferential, sequenceDeactivationVars[sequenceId]);
			// adds the term to the existing dual constraint representing the sequence
//			cplex.addToExpr(dualConstraints.get(sequenceId), expr);
			model.chgCoeff(dualConstraints.get(sequenceId), sequenceDeactivationVars[sequenceId], biggestDifferential);
		}
	}
	
	/**
	 * Outer method that loops over all informationSetIds and action pairs for the limited look-ahead player.
	 * @throws GRBException
	 */
//	private void addPrimalIncentiveConstraints() throws GRBException {
//		for (int informationSetId = game.getSmallestInformationSetId(playerNotToSolveFor); informationSetId < (numDualInformationSets + game.getSmallestInformationSetId(playerNotToSolveFor)); informationSetId++) {
//			// We need to access the set of actions available at the information set. To do this, we loop over node.actions on the (arbitrarily picked) first node in the information set.
//			int idOfFirstNodeInSet = game.getInformationSet(playerNotToSolveFor, informationSetId).get(0);
//			Node firstNodeInSet = game.getNodeById(idOfFirstNodeInSet);
//			// Dynamic programming table for expressions representing dominated actions
//			HashMap<Action, GRBLinExpr> dominatedActionExpressionTable = new HashMap<Action, GRBLinExpr>();
//			for (Action incentivizedAction : firstNodeInSet.getActions()) {
//				// get expr representing value of chosen action
//				GRBLinExpr incentiveExpr = getIncentivizedActionExpression(informationSetId, incentivizedAction);
//
//				GRBLinExpr incentiveConstraintLHS = new GRBLinExpr();
//				incentiveConstraintLHS.add(incentiveExpr);
//
//				int incentiveSequenceId = getSequenceIdForPlayerNotToSolveFor(informationSetId, incentivizedAction.getName());
//				// ensure that expression is only active if the sequence is chosen
//				//incentiveConstraintLHS.addTerm(maxEvaluationValueForSequence[incentiveSequenceId]+this.epsilon, sequenceDeactivationVars[incentiveSequenceId]);
//
//
//				for (Action dominatedAction : firstNodeInSet.getActions()) {
//					if (!incentivizedAction.equals(dominatedAction)) {
//						// get expr representing value of dominated action
//						GRBLinExpr dominatedExpr = getDominatedActionExpression(informationSetId, dominatedAction, dominatedActionExpressionTable);
//						GRBLinExpr incentiveConstraintRHS = new GRBLinExpr();
//						incentiveConstraintRHS.add(dominatedExpr);
//
//						int dominatedSequenceId = getSequenceIdForPlayerNotToSolveFor(informationSetId, dominatedAction.getName());
//						// ensure that expression is only active if the sequence is deactivated
//						incentiveConstraintRHS.addTerm(maxEvaluationValueForSequence[dominatedSequenceId]+this.epsilon, sequenceDeactivationVars[dominatedSequenceId]);
//						incentiveConstraintRHS.addTerm(-maxEvaluationValueForSequence[dominatedSequenceId], sequenceDeactivationVars[incentiveSequenceId]);
//						// TODO: something needs to be flipped here. Perhaps it needs to be LB of other side
//						incentiveConstraintRHS.setConstant(-maxEvaluationValueForSequence[dominatedSequenceId]);
//						cplex.addGe(incentiveConstraintLHS, incentiveConstraintRHS, "PrimalIncentive("+informationSetId+";"+incentivizedAction.getName()+";"+dominatedAction.getName()+")");
//
//						// add constraint requiring equality if both sequences are chosen to be incentivized, here we add one side of the two weak inequalities enforcing equality, since the other direction is also iterated over
//						GRBLinExpr equalityRHS = new GRBLinExpr();
//						equalityRHS.add(dominatedExpr);
//						equalityRHS.addTerm(-maxEvaluationValueForSequence[dominatedSequenceId], sequenceDeactivationVars[dominatedSequenceId]);
//						equalityRHS.addTerm(-maxEvaluationValueForSequence[dominatedSequenceId], sequenceDeactivationVars[incentiveSequenceId]);
//						cplex.addGe(incentiveExpr, equalityRHS, "quality("+informationSetId+";"+incentivizedAction.getName()+";"+dominatedAction.getName()+")");
//					}
//				}
//			}
//		}
//	}
	
	/**
	 * Using dynamic programming, this method computes an expression representing the value of a given action not chosen to be made optimal. This is done by creating an expression whose value is the sum of the value of descendant information sets under the action, according to the evaulation function and strategy for the rational player
	 * @param informationSetId
	 * @param dominatedAction the action to compute a value expression for
	 * @param dominatedActionExpressionTable dynamic programming table
	 * @return expression that represents the value of the action
	 * @throws GRBException
	 */
	private GRBLinExpr getDominatedActionExpression(int informationSetId, Action dominatedAction, HashMap<Action,GRBLinExpr> dominatedActionExpressionTable) throws GRBException {
		if (dominatedActionExpressionTable.containsKey(dominatedAction)) {
			return dominatedActionExpressionTable.get(dominatedAction);
		} else {
			// this expression represents the value of dominatedAction
			GRBLinExpr expr = new GRBLinExpr();
			//TIntObjectMap<GRBVar> informationSetToVariableMap = new TIntObjectHashMap<GRBVar>();
			TIntObjectMap<HashMap<String, IloRange>> rangeMap = new TIntObjectHashMap<HashMap<String, IloRange>>();
			GRBVar actionValueVar = cplex.numVar(-Double.MAX_VALUE, Double.MAX_VALUE, "DomActionValue;"+Integer.toString(informationSetId) + dominatedAction.getName());
			expr.addTerm(1, actionValueVar);
			IloRange range = cplex.addGe(actionValueVar, 0, actionValueVar.getName());
			// Iterate over nodes in information set and add value of each node for dominatedAction
			TIntArrayList informationSet = game.getInformationSet(playerNotToSolveFor, informationSetId);
			for (int i = 0; i < informationSet.size(); i++) {
				Node node = game.getNodeById(informationSet.get(i));
				// we need to locate the action that corresponds to dominatedAction at the current node, so that we can pull the correct childId
				Action dominatedActionForNode = node.getActions()[0];
				for (Action action : node.getActions()) {
					if (action.getName().equals(dominatedAction.getName())) dominatedActionForNode = action;
				}

				// find the descendant information sets and add their values to the expression
				//fillDominatedActionExpr(expr, dominatedActionForNode.getChildId(), informationSetToVariableMap, exprMap, 1, Integer.toString(informationSetId) + dominatedAction.getName());
				fillDominatedActionRange(range, dominatedActionForNode.getChildId(), rangeMap, 1, Integer.toString(informationSetId) + dominatedAction.getName());
			}
			// remember the expression for future method calls
			dominatedActionExpressionTable.put(dominatedAction, expr);
			
			return expr;
		}
	}
	
	private void fillDominatedActionRange(IloRange parentRange, int currentNodeId, TIntObjectMap<HashMap<String, IloRange>> rangeMap, int depth, String dominatedName) throws GRBException {
		Node node = game.getNodeById(currentNodeId);
		if (depth == lookAhead || node.isLeaf()) {
			int sequenceIdForRationalPlayer = playerToSolveFor == 1? sequenceIdForNodeP1[currentNodeId] : sequenceIdForNodeP2[currentNodeId];
			GRBLinExpr expr = new GRBLinExpr();
			expr.addTerm(-nodeNatureProbabilities[currentNodeId] * nodeEvaluationTable[currentNodeId], strategyVarsBySequenceId[sequenceIdForRationalPlayer]);
			cplex.addToExpr(parentRange, expr);
			return;
		}
		
		if (node.getPlayer() == playerNotToSolveFor && !rangeMap.containsKey(node.getInformationSet())) {
			// Create information set var and action expressions
			GRBVar informationSetValueVar = cplex.numVar(-Double.MAX_VALUE, Double.MAX_VALUE, "InfoValueVar;"+dominatedName+"("+node.getInformationSet()+")");
			// Add information set value var to the expression describing the value of the parent action
			GRBLinExpr infoValueExpr = new GRBLinExpr();
			infoValueExpr.addTerm(-1, informationSetValueVar);
			cplex.addToExpr(parentRange, infoValueExpr);
			
			//informationSetToVariableMap.put(node.getInformationSet(), informationSetValueVar);
			HashMap<String,IloRange> actionRangeMap = new HashMap<String,IloRange>();
			for (Action action : node.getActions()) {
				// Require information set value var to be >= value of each action
				IloRange actionValueRange = cplex.addGe(informationSetValueVar, 0, informationSetValueVar.getName() + ";" + action.getName());
				actionRangeMap.put(action.getName(), actionValueRange);
			}
			rangeMap.put(node.getInformationSet(), actionRangeMap);

		}

		for (Action action : node.getActions()) {
			//GRBLinExpr newActionExpr = node.getPlayer() == playerNotToSolveFor ? exprMap.get(node.getInformationSet()).get(action.getName()) : actionExpr;
			IloRange newParentRange = node.getPlayer() == playerNotToSolveFor ? rangeMap.get(node.getInformationSet()).get(action.getName()) : parentRange;
			fillDominatedActionRange(newParentRange, action.getChildId(), rangeMap, depth+1, dominatedName);
		}
	}
	

	
	private GRBLinExpr getIncentivizedActionExpression(int informationSetId, Action incentivizedAction) throws GRBException {
		GRBLinExpr actionExpr = new GRBLinExpr();
		int sequenceId = getSequenceIdForPlayerNotToSolveFor(informationSetId, incentivizedAction.getName());
		
		// Iterate over nodes in information set
		double maxHeuristicAtNode = 0;
		TIntObjectMap<HashMap<String, GRBVar>> exprMap = new TIntObjectHashMap<HashMap<String, GRBVar>>();
		TIntArrayList informationSet = game.getInformationSet(playerNotToSolveFor, informationSetId);
		
		//GRBVar actionValueVar = gurobi.numVar(-Double.MAX_VALUE, Double.MAX_VALUE, "IncActionValue;"+Integer.toString(informationSetId) + incentivizedAction.getName());
		//actionExpr.addTerm(1, actionValueVar);

		for (int i = 0; i < informationSet.size(); i++) {
			Node node = game.getNodeById(informationSet.get(i));
			// ensure that we are using the correct Action at the node, so we can pull the correct childId
			Action incentiveActionForNode = node.getActions()[0];
			for (Action action : node.getActions()) {
				if (action.getName().equals(incentivizedAction.getName())) incentiveActionForNode = action;
			}
			GRBVar actionActiveVar = cplex.boolVar("ActionActive" + Integer.toString(informationSetId) + incentivizedAction.getName());
			GRBLinExpr notDeactivatedExpr = new GRBLinExpr();
			notDeactivatedExpr.addTerm(-1, sequenceDeactivationVars[sequenceId]);
			notDeactivatedExpr.setConstant(1);
			cplex.addEq(actionActiveVar, notDeactivatedExpr, "NotDeactivated");
			fillIncentivizedActionExpression(actionExpr, actionActiveVar, incentiveActionForNode.getChildId(), exprMap, 1, Integer.toString(informationSetId) + incentivizedAction.getName());
			if (maxEvaluationValueForSequence[sequenceId] > maxHeuristicAtNode) {
				maxHeuristicAtNode = maxEvaluationValueForSequence[sequenceId];
			}
		}
		
		return actionExpr;
	}

	private void fillIncentivizedActionExpression(GRBLinExpr actionExpr, GRBVar parentSequence, int currentNodeId, TIntObjectMap<HashMap<String, GRBVar>> varActiveMap, int depth, String incentivizedName) throws GRBException {
		Node node = game.getNodeById(currentNodeId);
		if (depth == lookAhead || node.isLeaf()) {
			GRBVar nodeValueVar = cplex.numVar(-Double.MAX_VALUE, Double.MAX_VALUE, "NodeVal;"+incentivizedName+"("+node.getNodeId()+")");
			int sequenceIdForRationalPlayer = playerToSolveFor == 1? sequenceIdForNodeP1[currentNodeId] : sequenceIdForNodeP2[currentNodeId];
			// The heuristic value of a node for the limited look-ahead player is the evaluationTable value, weighted by probability of reaching the node, over both nature and the rational player
			GRBLinExpr nodeValueExpr = new GRBLinExpr();
			nodeValueExpr.addTerm(nodeNatureProbabilities[currentNodeId] * nodeEvaluationTable[currentNodeId], strategyVarsBySequenceId[sequenceIdForRationalPlayer]);
			
			GRBLinExpr nodeActiveExpr = new GRBLinExpr();
			nodeActiveExpr.addTerm(nodeNatureProbabilities[currentNodeId] * nodeEvaluationTable[currentNodeId], parentSequence);
			// the nodeValueVar is equal to the value of the node
			cplex.addLe(nodeValueVar, nodeValueExpr, "NodeVal;"+incentivizedName+"("+node.getNodeId()+")");
			// but it is only active if the parent sequence is active
			cplex.addLe(nodeValueVar, nodeActiveExpr, "NodeActive;"+incentivizedName+"("+node.getNodeId()+")");
			
			// finally, we add the value of the node to the expression representing the value of the incentivized action
			actionExpr.addTerm(1, nodeValueVar);
			return;
		}
		
		boolean visitedInformationSet = varActiveMap.containsKey(node.getInformationSet());
		
		if (node.getPlayer() == playerNotToSolveFor && !visitedInformationSet) {
			HashMap<String,GRBVar> actionMap = new HashMap<String,GRBVar>();
			GRBLinExpr sum = new GRBLinExpr();
			for (Action action : node.getActions()) {
				GRBVar actionActiveVar = cplex.boolVar("ActionActive" + incentivizedName + node.getInformationSet() + action.getName());
				// actionActiveVars should sum to 1
				sum.addTerm(1, actionActiveVar);
				// actionValueVar represents the value of the action according to the heuristic evaluation k steps ahead
				actionMap.put(action.getName(), actionActiveVar);
			}
			// actionActiveVars should sum to 1
			cplex.addEq(sum, 1);
			varActiveMap.put(node.getInformationSet(), actionMap);
		}
		for (Action action : node.getActions()) {
			GRBVar newParentSequence= node.getPlayer() == playerNotToSolveFor ? varActiveMap.get(node.getInformationSet()).get(action.getName()) : parentSequence;
			fillIncentivizedActionExpression(actionExpr, newParentSequence, action.getChildId(), varActiveMap, depth+1, incentivizedName);
		}
	}
	
	/**
	 * Recursive helper method for getIncentivizedActionExpression
	 * @param actionExpr
	 * @param currentNodeId
	 * @param exprMap
	 * @param depth
	 * @throws GRBException
	 */
	/*private void fillIncentivizedActionExpr(GRBLinExpr actionExpr, int currentNodeId, TIntObjectMap<HashMap<String, GRBLinExpr>> exprMap, int depth, String incentivizedName) throws GRBException {
		Node node = game.getNodeById(currentNodeId);
		if (depth == lookAhead || node.isLeaf()) {
			addLookAheadDepthEvaluationValueToExpression(actionExpr, currentNodeId);
			return;
		}
		boolean visited = exprMap.containsKey(node.getInformationSet());
		
		if (node.getPlayer() == playerNotToSolveFor && !visited) {
			HashMap<String,GRBLinExpr> actionMap = new HashMap<String,GRBLinExpr>();
			GRBLinExpr sum = gurobi.linearNumExpr();
			for (Action action : node.getActions()) {
				GRBVar actionActiveVar = gurobi.boolVar();
				// actionActiveVars should sum to 1
				sum.addTerm(1, actionActiveVar);
				// actionValueVar represents the value of the action according to the heuristic evaluation k steps ahead
				GRBVar actionValueVar = gurobi.numVar(-Double.MAX_VALUE, Double.MAX_VALUE, "Inc;"+incentivizedName+"("+node.getInformationSet()+";"+action.getName()+")");
				actionExpr.addTerm(1, actionValueVar);
				
				// Expression that takes on value 0 or M, where M is a sufficiently large constant to enable the full value of the action when active
				GRBLinExpr valueExpr = gurobi.linearNumExpr();
				valueExpr.addTerm(maxEvaluationValueForSequence[getSequenceIdForPlayerNotToSolveFor(node.getInformationSet(), action.getName())], actionActiveVar);
				
				// Force the real-valued actionValueVar to take on a non-zero value only if actionActiveVar == 1
				gurobi.addLe(actionValueVar, valueExpr);
				
				// Create a new actionExpr to recurse on and let actionValueVar be bounded by its value  
				GRBLinExpr newActionExpr = gurobi.linearNumExpr();
				gurobi.addLe(actionValueVar, newActionExpr);
				actionMap.put(action.getName(), newActionExpr);
			}
			// actionActiveVars should sum to 1
			gurobi.addEq(sum, 1);
			exprMap.put(node.getInformationSet(), actionMap);
		}
		for (Action action : node.getActions()) {
			GRBLinExpr newActionExpr = node.getPlayer() == playerNotToSolveFor ? exprMap.get(node.getInformationSet()).get(action.getName()) : actionExpr;
				
			fillIncentivizedActionExpr(newActionExpr, action.getChildId(), exprMap, depth+1, incentivizedName);

			if (node.getPlayer() == playerNotToSolveFor && !visited) {
				
			}

		}
	}*/

	/*private void addLookAheadDepthEvaluationValueToRange(IloRange range, int currentNodeId) throws GRBException {
			int sequenceIdForRationalPlayer = playerToSolveFor == 1? sequenceIdForNodeP1[currentNodeId] : sequenceIdForNodeP2[currentNodeId];
			// The heuristic value of a node for the limited look-ahead player is the evaluationTable value, weighted by probability of reaching the node, over both nature and the rational player 
			//expr.addTerm(nodeNatureProbabilities[currentNodeId] * nodeEvaluationTable[currentNodeId], strategyVarsBySequenceId[sequenceIdForRationalPlayer]);
			GRBLinExpr expr = gurobi.linearNumExpr();
			expr.addTerm(nodeNatureProbabilities[currentNodeId] * nodeEvaluationTable[currentNodeId], strategyVarsBySequenceId[sequenceIdForRationalPlayer]);
			gurobi.addToExpr(range, expr);
	}
	
	
	private void addLookAheadDepthEvaluationValueToExpression(GRBLinExpr expr, int currentNodeId) throws GRBException {
			int sequenceIdForRationalPlayer = playerToSolveFor == 1? sequenceIdForNodeP1[currentNodeId] : sequenceIdForNodeP2[currentNodeId];
			// The heuristic value of a node for the limited look-ahead player is the evaluationTable value, weighted by probability of reaching the node, over both nature and the rational player 
			expr.addTerm(nodeNatureProbabilities[currentNodeId] * nodeEvaluationTable[currentNodeId], strategyVarsBySequenceId[sequenceIdForRationalPlayer]);
	}*/

	
	
	private void computeMaxMinPayoff(int nodeId) {
		Node node = game.getNodeById(nodeId);
		if (node.isLeaf()) {
			if (node.getValue() > maxPayoff) {
				maxPayoff = node.getValue();
			}
			if (node.getValue() < minPayoff) {
				minPayoff = node.getValue();
			}
			return;
		}

		for (Action action : node.getActions()) {
			computeMaxMinPayoff(action.getChildId());
		}
	}

	private void computeMaxEvaluation() {
		Arrays.fill(maxEvaluationValueForSequence, 0);

		computeMaxEvaluationForAction(game.getRoot(), 0, 0);
		for (int informationSetId = 0; informationSetId < this.numDualInformationSets; informationSetId++) {
			for (int i = 0; i < game.getInformationSet(playerNotToSolveFor, informationSetId).size(); i++) {
				Node node = game.getNodeById(game.getInformationSet(playerNotToSolveFor, informationSetId).get(i));
				for (Action action : node.getActions()) {
					int sequenceId = getSequenceIdForPlayerNotToSolveFor(informationSetId, action.getName());
					computeMaxEvaluationForAction(action.getChildId(), sequenceId, 1);
				}
			}
		}
	}
	
	private void computeMaxEvaluationForAction(int nodeId, int sequenceId, int depth) {
		Node node = game.getNodeById(nodeId);
		if (depth == lookAhead || node.isLeaf()) {
			if (nodeEvaluationTable[nodeId] > maxEvaluationValueForSequence[sequenceId]) maxEvaluationValueForSequence[sequenceId] = nodeEvaluationTable[nodeId];
			return;
		}

		for (Action action : node.getActions()) {
			computeMaxEvaluationForAction(action.getChildId(), sequenceId, depth+1);
		}
	}

}
