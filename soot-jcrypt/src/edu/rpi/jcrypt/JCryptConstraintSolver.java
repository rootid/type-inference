package edu.rpi.jcrypt;

import java.util.*;

import static com.esotericsoftware.minlog.Log.info;

import java.lang.annotation.*;

import soot.ArrayType;
import soot.NullType;
import soot.SootClass;
import soot.SootMethod;
import edu.rpi.AnnotatedValue.FieldAdaptValue;
import edu.rpi.AnnotatedValue.MethodAdaptValue;
import edu.rpi.AnnotatedValue.AdaptValue;
import edu.rpi.AnnotatedValue.Kind;
import edu.rpi.*;
import edu.rpi.Constraint.SubtypeConstraint;
import edu.rpi.Constraint.EqualityConstraint;
import edu.rpi.Constraint.UnequalityConstraint;

import checkers.inference.reim.quals.*;

public class JCryptConstraintSolver extends AbstractConstraintSolver {

	protected JCryptTransformer st;

	protected Annotation READONLY = AnnotationUtils.fromClass(Readonly.class);

	protected Annotation POLYREAD = AnnotationUtils.fromClass(Polyread.class);

	private Map<String, Set<AdaptValue>> declRefToAdaptValue = new HashMap<String, Set<AdaptValue>>();

	private Map<String, Set<AdaptValue>> contextRefToAdaptValue = new HashMap<String, Set<AdaptValue>>();

	private Map<String, Set<Constraint>> lessValues = new HashMap<String, Set<Constraint>>();

	private Map<String, Set<Constraint>> greaterValues = new HashMap<String, Set<Constraint>>();

	private Set<Constraint> worklist = new LinkedHashSet<Constraint>();

	private boolean preferSource = false;

	private boolean preferSink = false;

	private BitSet updated = new BitSet(AnnotatedValue.maxId());
	// private BitSet restored = new BitSet(AnnotatedValue.maxId());

	private byte[] initAnnos = new byte[AnnotatedValue.maxId()];

	private final byte SENSITIVE_MASK = 0x01;
	private final byte POLY_MASK = 0x02;
	private final byte CLEAR_MASK = 0x04;
	// private Set<String> clearLibMethods;

	public JCryptConstraintSolver(InferenceTransformer t) {
		this(t, false);
	}

	public JCryptConstraintSolver(InferenceTransformer t, boolean b) {
		super(t, b);
		if (!(t instanceof JCryptTransformer))
			throw new RuntimeException("SFlowConstraintSolver expects JCryptTransformer");
		this.st = (JCryptTransformer) t;
		this.preferSource = (System.getProperty("preferSource") != null);
		this.preferSink = (System.getProperty("preferSink") != null);

		if (preferSink && preferSource) {
			throw new RuntimeException("Can only have one of {preferSource, preferSource}!");
		}
	}

	private byte toBits(Set<Annotation> annos) {
		byte b = 0;
		for (Annotation anno : annos) {
			if (anno.equals(st.SENSITIVE))
				b |= SENSITIVE_MASK;
			else if (anno.equals(st.POLY))
				b |= POLY_MASK;
			else if (anno.equals(st.CLEAR))
				b |= CLEAR_MASK;
		}
		return b;
	}

	private Set<Annotation> fromBits(byte b) {
		Set<Annotation> annos = AnnotationUtils.createAnnotationSet();
		if ((b & SENSITIVE_MASK) > 0)
			annos.add(st.SENSITIVE);
		if ((b & POLY_MASK) > 0)
			annos.add(st.POLY);
		if ((b & CLEAR_MASK) > 0)
			annos.add(st.CLEAR);
		return annos;
	}

	private void setInitAnnos(AnnotatedValue av) {
		int id = av.getId();
		initAnnos[id] = toBits(av.getAnnotations(st));
	}

	private Set<Annotation> getInitAnnos(int id) {
		byte b = initAnnos[id];
		return fromBits(b);
	}

	private boolean containsReadonly(AnnotatedValue av) {
		if (av instanceof AdaptValue)
			av = ((AdaptValue) av).getDeclValue();
		if (av.getType() == NullType.v())
			return true;

		AnnotatedValue reimValue = AnnotatedValueMap.v().get(av.getIdentifier());
		if (reimValue != null && (reimValue.containsAnno(READONLY)
		/* || reimValue.containsAnno(POLYREAD) */))
			return true;
		return false;
	}

	private Set<Constraint> getLessConstraints(AnnotatedValue av) {
		Set<Constraint> set = lessValues.get(av.getIdentifier());
		if (set == null)
			return Collections.<Constraint> emptySet();
		else
			return set;
	}

	private void addLessConstraint(AnnotatedValue av, Constraint less) {
		Set<Constraint> set = lessValues.get(av.getIdentifier());
		if (set == null) {
			set = new HashSet<Constraint>();
			lessValues.put(av.getIdentifier(), set);
		}
		set.add(less);
	}

	private Set<Constraint> getGreaterConstraints(AnnotatedValue av) {
		Set<Constraint> set = greaterValues.get(av.getIdentifier());
		if (set == null)
			return Collections.<Constraint> emptySet();
		else
			return set;
	}

	private void addGreaterConstraint(AnnotatedValue av, Constraint greater) {
		Set<Constraint> set = greaterValues.get(av.getIdentifier());
		if (set == null) {
			set = new HashSet<Constraint>();
			greaterValues.put(av.getIdentifier(), set);
		}
		set.add(greater);
	}

	private void buildRefToConstraintMapping(Constraint c) {
		AnnotatedValue left = null, right = null;
		if (c instanceof SubtypeConstraint || c instanceof EqualityConstraint || c instanceof UnequalityConstraint) {
			left = c.getLeft();
			right = c.getRight();
		}
		if (left != null && right != null) {
			AnnotatedValue[] refs = { left, right };
			for (AnnotatedValue ref : refs) {
				if (ref instanceof AdaptValue) {
					// Add decl and context
					AnnotatedValue decl = ((AdaptValue) ref).getDeclValue();
					AnnotatedValue context = ((AdaptValue) ref).getContextValue();
					// Add mapping from decl -> adaptValue
					Set<AdaptValue> adaptSet = declRefToAdaptValue.get(decl.getIdentifier());
					if (adaptSet == null) {
						adaptSet = new HashSet<AdaptValue>();
						declRefToAdaptValue.put(decl.getIdentifier(), adaptSet);
					}
					adaptSet.add((AdaptValue) ref);

					// Add mapping from context -> adaptValue
					if (!context.getIdentifier().startsWith(InferenceTransformer.CALLSITE_PREFIX)
							&& !context.getIdentifier().startsWith(InferenceTransformer.FAKE_PREFIX)) {
						Set<AdaptValue> adaptSet2 = contextRefToAdaptValue.get(context.getIdentifier());
						if (adaptSet2 == null) {
							adaptSet2 = new HashSet<AdaptValue>();
							contextRefToAdaptValue.put(context.getIdentifier(), adaptSet2);
						}
						adaptSet2.add((AdaptValue) ref);
					}
				}
			}
			if (c instanceof SubtypeConstraint) {
				addGreaterConstraint(left, c);
				addLessConstraint(right, c);
			} else if (c instanceof EqualityConstraint) {
				addGreaterConstraint(left, c);
				addLessConstraint(left, c);
				addGreaterConstraint(right, c);
				addLessConstraint(right, c);
			}
		}
	}

	private void buildRefToConstraintMapping(Set<Constraint> cons) {
		for (Constraint c : cons) {
			buildRefToConstraintMapping(c);
		}
	}

	private boolean canConnectVia(AnnotatedValue left, AnnotatedValue right) {
		if (left == null || right == null)
			return false;

		if (left.getKind() == Kind.LITERAL || right.getKind() == Kind.LITERAL)
			return false;

		SootMethod leftSm = left.getEnclosingMethod();
		SootMethod rightSm = right.getEnclosingMethod();

		if (leftSm != null && rightSm != null) {
			if (leftSm.equals(rightSm))
				return true;
			else if (leftSm.getName().equals(rightSm.getName())) {
				SootClass leftSc = left.getEnclosingClass();
				SootClass rightSc = right.getEnclosingClass();
				Set<SootClass> leftSuper = InferenceUtils.getSuperTypes(leftSc);
				if (leftSuper.contains(right))
					return true;

				Set<SootClass> rightSuper = InferenceUtils.getSuperTypes(rightSc);
				if (rightSuper.contains(right))
					return true;
			}
		}
		return false;
	}

	private boolean isParamOrRetValue(AnnotatedValue av) {
		Kind kind = av.getKind();
		return kind == Kind.PARAMETER || kind == Kind.THIS || kind == Kind.RETURN;
	}

	private boolean isLocalThis(AnnotatedValue av) {
		return av.getKind() == Kind.LOCAL && av.getName().equals("this");
	}

	private Set<Constraint> addLinearConstraints(Constraint con, Set<Constraint> existingCons) {
		Set<Constraint> newCons = new LinkedHashSet<Constraint>();
		if (!(con instanceof SubtypeConstraint))
			return newCons;
		Queue<Constraint> queue = new LinkedList<Constraint>();
		queue.add(con);
		while (!queue.isEmpty()) {
			Constraint c = queue.poll();
			AnnotatedValue left = c.getLeft();
			AnnotatedValue right = c.getRight();
			AnnotatedValue ref = null;
			List<Constraint> tmplist = new LinkedList<Constraint>();
			// Step 1: field read
			// Nov 26, 2013: add linear constraint for array fields
			if ((left instanceof FieldAdaptValue)) {
				if ((ref = ((FieldAdaptValue) left).getDeclValue()) != null
						&& (ref.getAnnotations(st).size() == 1 && ref.getAnnotations(st).contains(st.POLY)
								|| ((FieldAdaptValue) left).getContextValue().getType() instanceof ArrayType)) {
					Constraint linear = new SubtypeConstraint(((FieldAdaptValue) left).getContextValue(), right);
					linear.addCause(c);
					tmplist.add(linear);
				}
				for (Constraint lc : getLessConstraints(left)) {
					AnnotatedValue r = lc.getLeft();
					if (!r.equals(right) && !(r instanceof MethodAdaptValue)) {
						Constraint linear = new SubtypeConstraint(r, right);
						linear.addCause(lc);
						linear.addCause(c);
						tmplist.add(linear);
					}
				}
			}
			// Step 2: field write
			// Nov 26, 2013: add linear constraint for array fields
			else if ((right instanceof FieldAdaptValue)) {
				if ((ref = ((FieldAdaptValue) right).getDeclValue()) != null
						&& (ref.getAnnotations(st).size() == 1 && ref.getAnnotations(st).contains(st.POLY)
								// || preferSource // FIXED fields on May 7,
								// 2014
								|| ((FieldAdaptValue) right).getContextValue().getType() instanceof ArrayType)) {
					Constraint linear = new SubtypeConstraint(left, ((FieldAdaptValue) right).getContextValue());
					linear.addCause(c);
					tmplist.add(linear);
				}
				for (Constraint gc : getGreaterConstraints(right)) {
					AnnotatedValue r = gc.getRight();
					if (!left.equals(r) && !(r instanceof MethodAdaptValue)) {
						Constraint linear = new SubtypeConstraint(left, r);
						linear.addCause(c);
						linear.addCause(gc);
						tmplist.add(linear);
					}
				}
			}
			// Step 3: linear constraints
			else if (!(left instanceof MethodAdaptValue) && !(right instanceof MethodAdaptValue)
					&& canConnectVia(left, right)) {
				for (Constraint lc : getLessConstraints(left)) {
					AnnotatedValue r = lc.getLeft();
					if (!r.equals(right) && (isParamOrRetValue(r) || isLocalThis(r))
							&& !(r instanceof MethodAdaptValue)) {
						Constraint linear = new SubtypeConstraint(r, right);
						linear.addCause(lc);
						linear.addCause(c);
						tmplist.add(linear);
					}
				}
				if ((isParamOrRetValue(left) || isLocalThis(left))) {
					for (Constraint gc : getGreaterConstraints(right)) {
						AnnotatedValue r = gc.getRight();
						if (!left.equals(r) && !(r instanceof MethodAdaptValue)) {
							Constraint linear = new SubtypeConstraint(left, r);
							linear.addCause(c);
							linear.addCause(gc);
							tmplist.add(linear);
						}
					}
				}
				// step 4: z <: (y |> par) |> (y |> ret) |> x
				// if c is a new linear constraint between parameters
				// and returns, look for method adapt constraints
				if ((isParamOrRetValue(left) || isLocalThis(left))
						&& (isParamOrRetValue(right) || isLocalThis(right))) {
					// /return/param/this -> return/param/this
					Set<AdaptValue> adaptSetLeft = declRefToAdaptValue.get(left.getIdentifier());
					Set<AdaptValue> adaptSetRight = declRefToAdaptValue.get(right.getIdentifier());
					if (adaptSetLeft != null && adaptSetRight != null) {
						for (AdaptValue yPar : adaptSetLeft) {
							// if
							// (clearLibMethods.contains(yPar.getIdentifier()))
							// continue;
							for (AdaptValue yRet : adaptSetRight) {
								// if
								// (clearLibMethods.contains(yRet.getIdentifier()))
								// continue;
								if (yPar.getContextValue().getId() == yRet.getContextValue().getId()) {
									for (Constraint lc : getLessConstraints(yPar)) {
										for (Constraint gc : getGreaterConstraints(yRet)) {
											Constraint linear = new SubtypeConstraint(lc.getLeft(), gc.getRight());
											linear.addCause(lc);
											linear.addCause(c);
											linear.addCause(gc);
											tmplist.add(linear);
										}
									}
								}
							}
						}
					}
				}
			}
			// if (!tmplist.isEmpty()) System.out.println(c);
			for (Constraint linear : tmplist) {
				if (!existingCons.contains(linear) && !newCons.contains(linear)
						&& canConnectVia(linear.getLeft(), linear.getRight())) {
					newCons.add(linear);
					buildRefToConstraintMapping(linear);
					queue.add(linear);
					// System.out.println(linear);
				}
			}
			// if (!tmplist.isEmpty()) System.out.println();
		}
		return newCons;
	}

	private void updateConstraintsWithReim(Set<Constraint> cons) {
		Set<Constraint> newCons = new LinkedHashSet<Constraint>();
		for (Constraint c : cons) {
			newCons.add(c);
			if (c instanceof SubtypeConstraint) {
				AnnotatedValue sub = c.getLeft();
				AnnotatedValue sup = c.getRight();
				if (!containsReadonly(sub) && !containsReadonly(sup)) {
					// If this constraint is from library method, skip
					// it.
					if (isParamOrRetValue(sub) && isParamOrRetValue(sup)
							&& st.isLibraryMethod((SootMethod) sub.getValue())
							&& st.isLibraryMethod((SootMethod) sup.getValue())) {
						// skip
					} else {
						// need equality constraint: just add the reverse
						Constraint reverse = new SubtypeConstraint(sup, sub);
						newCons.add(reverse);
					}
				}
				// special case: x = new String(y);
				// jimple: specialinvoke x.<java.lang.String: void <init>(java.lang.String)>(y);
				// if y is sensitive, x should be sensitive too
			}
		}
		cons.clear();
		cons.addAll(newCons);
	}

	/**
	 * Retore a Value to its initial annos
	 */
	private boolean makeSatisfiable(Constraint c) {
		return makeSatisfiable(c, true);
	}

	private int restoreCounter = 0;

	/**
	 * Retore a Value to its initial annos or to all
	 */
	private boolean makeSatisfiable(Constraint c, boolean beenUpdated) {
		AnnotatedValue toUpdate = null;
		if (preferSink) {
			if (getAnnotations(c.getLeft()).contains(st.SENSITIVE) || (getAnnotations(c.getLeft()).contains(st.POLY)
					&& getAnnotations(c.getRight()).contains(st.CLEAR)))
				toUpdate = c.getRight();
			else
				toUpdate = c.getLeft();
		} else if (preferSource) {
			if (getAnnotations(c.getRight()).contains(st.CLEAR) || (getAnnotations(c.getRight()).contains(st.POLY)
					&& getAnnotations(c.getLeft()).contains(st.SENSITIVE)))
				toUpdate = c.getLeft();
			else
				toUpdate = c.getRight();
		}
		boolean needSolve = false;
		if (toUpdate == null)
			return needSolve;
		AnnotatedValue[] avs;
		if (toUpdate instanceof MethodAdaptValue) {
			avs = new AnnotatedValue[] { ((AdaptValue) toUpdate).getContextValue(),
					((AdaptValue) toUpdate).getDeclValue() };
		} else if (toUpdate instanceof FieldAdaptValue) {
			// skip
			AnnotatedValue fieldAv = ((AdaptValue) toUpdate).getDeclValue();
			Set<Annotation> set = fieldAv.getAnnotations(st);
			if (preferSource && set.size() == 1 && set.contains(st.SENSITIVE))
				avs = new AnnotatedValue[] { fieldAv };
			else
				avs = new AnnotatedValue[] { ((AdaptValue) toUpdate).getContextValue() };
			// System.out.println("Restoring " + c);
		} else
			avs = new AnnotatedValue[] { toUpdate };

		for (AnnotatedValue av : avs) {
			int id = av.getId();
			if (beenUpdated) {
				// only restore values that have been updated before
				if (updated.get(id) && av.getRestoreNum() < 3) {
					Set<Annotation> initAnnos = getInitAnnos(id);
					// System.out.println("Restoring " + av + " to " +
					// initAnnos);
					// restore
					av.setAnnotations(initAnnos, st);
					needSolve = true;
					restoreCounter++;
					av.setRestored();
				}
			} else if (av.getKind() != Kind.CONSTANT) {
				// restore values that have never been updated
				// this essentially removes sources or sinks
				System.out.println("INFO: Eliminating a SOURCE/SINK: " + av);
				av.setAnnotations(st.getSourceLevelQualifiers(), st);
				// here we need to restore all constraints?
				for (AnnotatedValue v : AnnotatedValueMap.v().values()) {
					if (updated.get(id)) {
						Set<Annotation> initAnnos = getInitAnnos(id);
						v.setAnnotations(initAnnos, st);
						updated.flip(id);
					}
				}
				needSolve = true;
			}
		}

		if (!needSolve)
			return false;

		// solve again
		boolean b = false;
		try {
			b = handleConstraint(c);
		} catch (Exception e) {
		}
		return b;
	}

	private void addToWorklist(Set<AnnotatedValue> avs) {
		for (AnnotatedValue av : avs)
			addToWorklist(av);
	}

	private void addToWorklist(AnnotatedValue av) {
		worklist.addAll(getLessConstraints(av));
		worklist.addAll(getGreaterConstraints(av));
		Set<AdaptValue> set = declRefToAdaptValue.get(av.getIdentifier());
		if (set != null) {
			Set<AnnotatedValue> avs = new HashSet<AnnotatedValue>();
			for (AdaptValue a : set)
				avs.add(a);
			addToWorklist(avs);
		}
		set = contextRefToAdaptValue.get(av.getIdentifier());
		if (set != null) {
			Set<AnnotatedValue> avs = new HashSet<AnnotatedValue>();
			for (AdaptValue a : set)
				avs.add(a);
			addToWorklist(avs);
		}
	}

	@Override
	protected boolean setAnnotations(AnnotatedValue av, Set<Annotation> annos) throws SolverException {
		Set<Annotation> oldAnnos = av.getAnnotations(st);
		if (av instanceof AdaptValue)
			return setAnnotations((AdaptValue) av, annos);
		if (oldAnnos.equals(annos))
			return false;
		if (av.getKind() == Kind.CONSTANT || av.getIdentifier().startsWith(InferenceTransformer.CALLSITE_PREFIX)
				|| av.getIdentifier().startsWith(InferenceTransformer.FAKE_PREFIX))
			return false;

		if (!updated.get(av.getId())) {
			// first update, remember the initial annos
			updated.set(av.getId());
			setInitAnnos(av);
		}

		// Add related constraints to worklist
		addToWorklist(av);

		return super.setAnnotations(av, annos);
	}

	@Override
	protected Set<Constraint> solveImpl() {
		Set<Constraint> constraints = st.getConstraints();
		info(this.getClass().getSimpleName(), "Solving JCrypt constraints:  " + constraints.size() + " in total...");

		// try using reim
		updateConstraintsWithReim(constraints);

		// add constrains between map output and reduce input
		//addMapReduceConstraints(constraints);

		buildRefToConstraintMapping(constraints);

		worklist.addAll(constraints);

		Set<Constraint> warnConstraints = new HashSet<Constraint>();
		Set<Constraint> conflictConstraints = new LinkedHashSet<Constraint>();
		boolean hasUpdate = false;

		while (!worklist.isEmpty()) {
			Iterator<Constraint> it = worklist.iterator();
			Constraint c = it.next();
			it.remove();
			try {
				hasUpdate = handleConstraint(c) || hasUpdate;
				Set<Constraint> newCons = addLinearConstraints(c, constraints);
				worklist.addAll(newCons);
				constraints.addAll(newCons);
				// if (hasUpdate) worklist.addAll(constraints);
			} catch (SolverException e) {
				FailureStatus fs = t.getFailureStatus(c);
				if (fs == FailureStatus.ERROR) {
					if (preferSource || preferSink) {
						if (!makeSatisfiable(c) && constraints.contains(c))
							conflictConstraints.add(c);
					} else if (constraints.contains(c))
						conflictConstraints.add(c);
				} else if (fs == FailureStatus.WARN) {
					if (!warnConstraints.contains(c)) {
						System.out.println("WARN: handling constraint " + c + " failed.");
						warnConstraints.add(c);
					}
				}
			}
		}
		// Now make sure conflictConstraints are not satisfiable
		for (Iterator<Constraint> it = conflictConstraints.iterator(); it.hasNext();) {
			Constraint c = it.next();
			try {
				handleConstraint(c);
				it.remove();
			} catch (SolverException e) {
			}
		}
		info(this.getClass().getSimpleName(), "Total restore number: " + restoreCounter);
		info(this.getClass().getSimpleName(),
				"Finish solving JCrypt constraints. " + conflictConstraints.size() + " error(s)");

		return conflictConstraints;
	}

//	private void addMapReduceConstraints(Set<Constraint> constraints) {
//		String out1 = "lib-<org.apache.hadoop.mapreduce.TaskInputOutputContext: void write(java.lang.Object,java.lang.Object)>@parameter";
//		String out2 = "lib-<org.apache.hadoop.mapred.OutputCollector: void collect(java.lang.Object,java.lang.Object)>@parameter";
//		String outkey1 = out1 + "0", outkey2 = out2 + "0";
//		String outvalue1 = out1 + "1", outvalue2 = out2 + "1";
//		Set<AnnotatedValue> mapOutKeys = new HashSet<>(), mapOutValues = new HashSet<>();
//		Set<AnnotatedValue> reduceOutKeys = new HashSet<>(), reduceOutValues = new HashSet<>();
//		Set<AnnotatedValue> combineOutKeys = new HashSet<>(), combineOutValues = new HashSet<>();
//		Set<AnnotatedValue> partitionInKeys = new HashSet<>(), partitionInValues = new HashSet<>();
//		Set<AnnotatedValue> reduceInKeys = new HashSet<>(), reduceInValues = new HashSet<>();
//		Set<AnnotatedValue> combineInKeys = new HashSet<>(), combineInValues = new HashSet<>();
//		getKeyValues(constraints, outkey1, outkey2, outvalue1, outvalue2, mapOutKeys, mapOutValues, reduceOutKeys,
//				reduceOutValues, combineOutKeys, combineOutValues, reduceInKeys, combineInKeys, partitionInKeys, reduceInValues,
//				combineInValues, partitionInValues);
//		// add constraints
//		for (AnnotatedValue mapOutKey : mapOutKeys) {
//			for (AnnotatedValue combineInKey : combineInKeys)
//				constraints.add(new SubtypeConstraint(mapOutKey, combineInKey));
//			for (AnnotatedValue partitionInKey : partitionInKeys)
//				constraints.add(new SubtypeConstraint(mapOutKey, partitionInKey));
//			if (combineInKeys.isEmpty())
//				for (AnnotatedValue reduceInKey : reduceInKeys)
//					constraints.add(new SubtypeConstraint(mapOutKey, reduceInKey));
//		}
//		for (AnnotatedValue combineOutKey : combineOutKeys)
//			for (AnnotatedValue reduceInKey : reduceInKeys)
//				constraints.add(new SubtypeConstraint(combineOutKey, reduceInKey));
//		for (AnnotatedValue mapOutValue : mapOutValues) {
//			for (AnnotatedValue combineInValue : combineInValues)
//				constraints.add(new SubtypeConstraint(mapOutValue, combineInValue));
//			for (AnnotatedValue partitionInValue : partitionInValues)
//				constraints.add(new SubtypeConstraint(mapOutValue, partitionInValue));
//			if (combineInValues.isEmpty())
//				for (AnnotatedValue reduceInValue : reduceInValues)
//					constraints.add(new SubtypeConstraint(mapOutValue, reduceInValue));
//		}
//		for (AnnotatedValue combineOutValue : combineOutValues)
//			for (AnnotatedValue reduceInValue : reduceInValues)
//				constraints.add(new SubtypeConstraint(combineOutValue, reduceInValue));
//	}
//
//	// get mapper, combiner and reducer input/output keys/values
//	private void getKeyValues(Set<Constraint> constraints, String outkey1, String outkey2, String outvalue1,
//			String outvalue2, Set<AnnotatedValue> mapOutKeys, Set<AnnotatedValue> mapOutValues,
//			Set<AnnotatedValue> reduceOutKeys, Set<AnnotatedValue> reduceOutValues, Set<AnnotatedValue> combineOutKeys,
//			Set<AnnotatedValue> combineOutValues, Set<AnnotatedValue> reduceInKeys, Set<AnnotatedValue> combineInKeys,
//			Set<AnnotatedValue> partitionInKeys, Set<AnnotatedValue> reduceInValues, Set<AnnotatedValue> combineInValues,
//			Set<AnnotatedValue> partitionInValues) {
//		Map<String, Set<String>> mapreduceClasses = JCryptTransformer.mapreduceClasses;
//		for (Constraint c : constraints) {
//			AnnotatedValue left = c.getLeft(), right = c.getRight();
//			String className = left.getEnclosingClass().getName();
//			if (right.getKind() == Kind.METH_ADAPT) {
//				String declId = ((AdaptValue) right).getDeclValue().getIdentifier();
//				if (declId.equals(outkey1) || declId.equals(outkey2)) {
//					if (mapreduceClasses.get("setMapperClass").contains(className)) {
//						mapOutKeys.add(left);
//					} else if (mapreduceClasses.get("setReducerClass").contains(className)) {
//						reduceOutKeys.add(left);
//					} else if (mapreduceClasses.get("setCombinerClass").contains(className)) {
//						combineOutKeys.add(left);
//					}
//				} else if (declId.equals(outvalue1) || declId.equals(outvalue2)) {
//					if (mapreduceClasses.get("setMapperClass").contains(className)) {
//						mapOutValues.add(left);
//					} else if (mapreduceClasses.get("setReducerClass").contains(className)) {
//						reduceOutValues.add(left);
//					} else if (mapreduceClasses.get("setCombinerClass").contains(className)) {
//						combineOutValues.add(left);
//					}
//				}
//			} else if (left.getKind() == Kind.PARAMETER) {
//				String leftId = left.getIdentifier();
//				if (leftId.endsWith("@parameter0")) {
//					if (leftId.contains("void reduce")) {
//						if (mapreduceClasses.get("setReducerClass").contains(className)) {
//							reduceInKeys.add(right);
//						} else if (mapreduceClasses.get("setCombinerClass").contains(className)) {
//							combineInKeys.add(right);
//						}
//					} else if (leftId.contains("int getPartition")) {
//						partitionInKeys.add(right);
//					}
//				}
//				if (leftId.endsWith("@parameter1")) {
//					if (leftId.contains("void reduce")) {
//						if (mapreduceClasses.get("setReducerClass").contains(className)) {
//							reduceInValues.add(right);
//						} else if (mapreduceClasses.get("setCombinerClass").contains(className)) {
//							combineInValues.add(right);
//						}
//					} else if (leftId.contains("int getPartition")) {
//						partitionInValues.add(right);
//					}
//				}
//			}
//		}
//	}

}
