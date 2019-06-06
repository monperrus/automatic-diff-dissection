package add.features.detector.repairpatterns;

import java.util.ArrayList;
import java.util.List;

import com.github.gumtreediff.tree.ITree;

import add.entities.PatternInstance;
import add.entities.PropertyPair;
import add.entities.RepairPatterns;
import add.features.detector.spoon.RepairPatternUtils;
import add.features.detector.spoon.SpoonHelper;
import add.features.detector.spoon.filter.NullCheckFilter;
import add.main.Config;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import gumtree.spoon.diff.operations.DeleteOperation;
import gumtree.spoon.diff.operations.InsertOperation;
import gumtree.spoon.diff.operations.Operation;
import gumtree.spoon.diff.operations.UpdateOperation;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTargetedExpression;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.LineFilter;
import spoon.reflect.visitor.filter.TypeFilter;

public class WrongReferenceDetector extends AbstractPatternDetector {

	public static final String WRONG_VAR_REF = "wrongVarRef";
	public static final String WRONG_METHOD_REF = "wrongMethodRef";
	public static final String UNWRAP_METHOD = "unwrapMethod";
	public static final String WRAPS_METHOD = "wrapsMethod";
	public static final String CONST_CHANGE = "constChange";
	public static final String WRAPS_IF_ELSE = "wrapsIfElse";
	public static final String UNWRAP_IF_ELSE = "unwrapIfElse";
	public static final String MISS_NULL_CHECK_N = "missNullCheckN";
	public static final String MISS_NULL_CHECK_P = "missNullCheckP";

	private Config config;

	public WrongReferenceDetector(Config config, List<Operation> operations) {
		super(operations);
		this.config = config;
	}

	@Override
	public void detect(RepairPatterns repairPatterns) {
		for (int i = 0; i < operations.size(); i++) {

			Operation operation = operations.get(i);
			if (operation instanceof DeleteOperation) {
				Operation operationDelete = operation;
				CtElement srcNode = operationDelete.getSrcNode();
					if (srcNode instanceof CtVariableAccess 
							|| srcNode instanceof CtInvocation || srcNode instanceof CtConstructorCall||
							srcNode instanceof CtTypeAccess) {
					 if (srcNode.getMetadata("delete") != null) {
						CtElement statementParent = srcNode.getParent(CtStatement.class);
						if (statementParent.getMetadata("delete") == null) {
							CtElement newElementReplacementOfTheVar = null;
							boolean wasVariableWrapped = false;
							
							for (int j = 0; j < operations.size(); j++) {
								Operation operation2 = operations.get(j);
								if (operation2 instanceof InsertOperation) {
									CtElement node2 = operation2.getSrcNode();
									if (node2 instanceof CtInvocation || node2 instanceof CtConstructorCall) {
										if (((InsertOperation) operation2).getParent() != null) {
											if (srcNode.getParent() == ((InsertOperation) operation2).getParent()) {
												wasVariableWrapped = whethervarwrapped(srcNode, node2);
											}
										}
									}
		
									if (srcNode.getParent() == ((InsertOperation) operation2).getParent()) {
										newElementReplacementOfTheVar = node2;
									}
								}
							}
							
							if (!wasVariableWrapped) {

								CtElement susp = operationDelete.getSrcNode();
								CtElement patch = null;

								ITree parentRight = MappingAnalysis.getParentInRight(diff, operationDelete.getAction());
								if (parentRight != null) {
									patch = (CtElement) parentRight.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);
								}

								CtElement parentLine = MappingAnalysis.getParentLine(new LineFilter(), susp);
								ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parentLine);

								PropertyPair[] metadata = null;

								PropertyPair propertyOldElemet = new PropertyPair("Old", "Removed_"
										+ srcNode.getClass().getSimpleName().replace("Ct", "").replace("Impl", ""));

								String replaceElementType="";
								// if we have the element that has be inserted
								if (newElementReplacementOfTheVar != null) {
									PropertyPair propertyNewElement = new PropertyPair("New",
											"Added_" + newElementReplacementOfTheVar.getClass().getSimpleName()
													.replace("Ct", "").replace("Impl", ""));
									replaceElementType=newElementReplacementOfTheVar.getClass().getSimpleName()
											.replace("Ct", "").replace("Impl", "");

									metadata = new PropertyPair[] { propertyOldElemet, propertyNewElement };
								} else
									metadata = new PropertyPair[] { propertyOldElemet };
                                
								Boolean whetherConsiderInitial=false;
								
								if(metadata.length==2) {
									
									if(newElementReplacementOfTheVar instanceof CtFieldRead) {
										
										if(whethervariablenewlydeclared((CtFieldRead)newElementReplacementOfTheVar))
											continue;
									}
										
                                    if(newElementReplacementOfTheVar instanceof CtFieldWrite) {
										
										if(whethervariablenewlydeclared((CtFieldWrite)newElementReplacementOfTheVar))
											continue;
									}
                                    
                                    if(newElementReplacementOfTheVar instanceof CtVariableRead) {
										
										if(whethervariablenewlydeclared((CtVariableRead)newElementReplacementOfTheVar))
											continue;
									}
										
                                    if(newElementReplacementOfTheVar instanceof CtVariableWrite) {
										
										if(whethervariablenewlydeclared((CtVariableWrite)newElementReplacementOfTheVar))
											continue;
									}
									
									if(replaceElementType.equals("Invocation")||replaceElementType.equals("VariableRead")
											||replaceElementType.equals("FieldRead")||replaceElementType.equals("ConstructorCall")
											||replaceElementType.equals("Literal")||replaceElementType.equals("FieldWrite")
											||replaceElementType.equals("VariableWrite")||replaceElementType.equals("TypeAccess"))
										whetherConsiderInitial=true;
								}
								
								if(whetherConsiderInitial) {

								   if (srcNode instanceof CtInvocation) {
									   
									   if(newElementReplacementOfTheVar instanceof CtInvocation &&  
											((CtInvocation)srcNode).getExecutable().getSimpleName().
											equals(((CtInvocation)newElementReplacementOfTheVar).getExecutable().getSimpleName())
											&&((CtInvocation) srcNode).getArguments().size()==((CtInvocation)newElementReplacementOfTheVar).getArguments().size())
									   { }
									   else {
										   if(newElementReplacementOfTheVar instanceof CtInvocation) {
											   
											   String srcCallMethodName=((CtInvocation)srcNode).getExecutable().getSimpleName();
											   String dstCallMethodName=((CtInvocation)newElementReplacementOfTheVar).
													   getExecutable().getSimpleName();
											   
											   if (!srcCallMethodName.equals(dstCallMethodName)) {
													repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
															new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
																	lineTree, new PropertyPair("Change", "differentMethodName")));

												} else {					
													repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
															new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
																lineTree,new PropertyPair("Change", "SameNamedifferentArgument")));
												}
										   }
										   else
										        repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF, new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp,
												     parentLine, lineTree, metadata));
									   }
								   
								       if(newElementReplacementOfTheVar instanceof CtInvocation) {
								    	   
								    	    List<CtExpression> invocationArgumentsold = new ArrayList<>();
											invocationArgumentsold = ((CtInvocation) srcNode).getArguments();
											
											List<CtExpression> invocationArgumentnew = new ArrayList<>();
											invocationArgumentnew = ((CtInvocation) newElementReplacementOfTheVar).getArguments();

											detectVarArgumentChange(invocationArgumentsold, invocationArgumentnew, repairPatterns, srcNode, newElementReplacementOfTheVar);
								       }
								       
								       if(newElementReplacementOfTheVar instanceof CtConstructorCall) {
								    	    List<CtExpression> invocationArgumentsold = new ArrayList<>();
											invocationArgumentsold = ((CtInvocation) srcNode).getArguments();
											
											List<CtExpression> invocationArgumentnew = new ArrayList<>();
											invocationArgumentnew = ((CtConstructorCall) newElementReplacementOfTheVar).getArguments();

											detectVarArgumentChange(invocationArgumentsold, invocationArgumentnew, repairPatterns, srcNode, newElementReplacementOfTheVar);
								       }
								    }
								  else  if (srcNode instanceof CtConstructorCall) {
									  
								   String[] namespace=((CtConstructorCall) srcNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
								   String originalconstructorname =namespace [namespace.length-1];	
								   
								   String[] namespacenew=((CtConstructorCall) newElementReplacementOfTheVar).getExecutable().getSignature().split("\\(")[0].split("\\.");
								   String newconstructorname =namespacenew [namespacenew.length-1];	
										
								   if(newElementReplacementOfTheVar instanceof CtConstructorCall &&  
										originalconstructorname.equals(newconstructorname)
										&&((CtConstructorCall) srcNode).getArguments().size()==((CtConstructorCall)newElementReplacementOfTheVar).getArguments().size())
								   { }
								   else  {
									   
									   if(newElementReplacementOfTheVar instanceof CtConstructorCall) {
										   
										   if (!originalconstructorname.equals(newconstructorname)) {
												repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
														new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
																lineTree, new PropertyPair("Change", "differentMethodName")));

											} else {					
												repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
														new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
															lineTree,new PropertyPair("Change", "SameNamedifferentArgument")));
											}
									   }
									   else
									      repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
									         new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp,
											 parentLine, lineTree, metadata));
								   }
							   
							       if(newElementReplacementOfTheVar instanceof CtConstructorCall) {
							    	   
							    	    List<CtExpression> invocationArgumentsold = new ArrayList<>();
										invocationArgumentsold = ((CtConstructorCall) srcNode).getArguments();
										
										List<CtExpression> invocationArgumentnew = new ArrayList<>();
										invocationArgumentnew = ((CtConstructorCall) newElementReplacementOfTheVar).getArguments();
											
										detectVarArgumentChange(invocationArgumentsold, invocationArgumentnew, repairPatterns, srcNode, newElementReplacementOfTheVar);
							       }
							       
							       if(newElementReplacementOfTheVar instanceof CtInvocation) {
							    	    List<CtExpression> invocationArgumentsold = new ArrayList<>();
										invocationArgumentsold = ((CtConstructorCall) srcNode).getArguments();
										
										List<CtExpression> invocationArgumentnew = new ArrayList<>();
										invocationArgumentnew = ((CtInvocation) newElementReplacementOfTheVar).getArguments();
											
										detectVarArgumentChange(invocationArgumentsold, invocationArgumentnew, repairPatterns, srcNode, newElementReplacementOfTheVar);
							       }
							    }
								  else
									  repairPatterns.incrementFeatureCounterInstance(WRONG_VAR_REF,
											new PatternInstance(WRONG_VAR_REF, operationDelete, patch, susp, parentLine,
													lineTree, metadata));
							    }
								
								if(!whetherConsiderInitial && metadata.length==1) {
                                     if(susp.getParent() instanceof CtInvocation || susp.getParent() instanceof CtConstructorCall) {
         								CtElement susp_parent = susp.getParent();
         								if(!whetherparentchanged(susp_parent)) {
         									repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
         											new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp_parent,
         													parentLine, lineTree, new PropertyPair("Change", "SameNamedifferentArgument")));
         								 }
                                    }
								}
							}
						}
					}
				}
			}
			
			if (operation instanceof UpdateOperation) {
				CtElement srcNode = operation.getSrcNode();
				CtElement dstNode = operation.getDstNode();

				if (dstNode.getParent().getMetadata("new") != null
						|| dstNode.getParent().getMetadata("isMoved") != null) {
					continue;
				}
				if (srcNode.getParent().getMetadata("new") != null
						|| srcNode.getParent().getMetadata("isMoved") != null) {
					continue;
				}

				if (srcNode instanceof CtVariableAccess || srcNode instanceof CtTypeAccess) {
					if (operation.getDstNode() instanceof CtVariableAccess
							|| operation.getDstNode() instanceof CtTypeAccess
							) {
						
						if(srcNode instanceof CtVariableAccess && operation.getDstNode() instanceof CtVariableAccess) {

							if(whethervariableDeclarationUpdated((CtVariableAccess)srcNode, (CtVariableAccess)operation.getDstNode()))
								continue;
							
							if(whethervariablenewlydeclared((CtVariableAccess)operation.getDstNode()))
								continue;
						}
						
						CtElement susp = operation.getSrcNode();
						CtElement patch = null;

						CtElement parentLine = MappingAnalysis.getParentLine(new LineFilter(), susp);
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parentLine);

						ITree parentRight = MappingAnalysis.getParentInRight(diff, operation.getAction());
						if (parentRight != null) {
							patch = (CtElement) parentRight.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);
						}

						repairPatterns.incrementFeatureCounterInstance(WRONG_VAR_REF, new PatternInstance(WRONG_VAR_REF,
								operation, patch, susp, parentLine, lineTree, new PropertyPair("Change", "Update_"
										+ srcNode.getClass().getSimpleName().replace("Ct", "").replace("Impl", ""))));
					}
				}
				
				if (!(srcNode instanceof CtInvocation) && !(srcNode instanceof CtConstructorCall)) {
					continue;
				}
				
				if(srcNode instanceof CtInvocation && srcNode.toString().startsWith("this(")) {
					if(!(srcNode instanceof CtInvocation) || !dstNode.toString().startsWith("this("))
						continue;
				}

				if (dstNode instanceof CtInvocation || dstNode instanceof CtConstructorCall) {

				     String srcCallMethodName;
				     CtTargetedExpression srcInvocation = (CtTargetedExpression) srcNode;
				
				     if(srcNode instanceof CtInvocation) {
					    srcCallMethodName = ((CtInvocation) srcNode).getExecutable().getSimpleName();
				     } else {	
					    String[] namespace=((CtConstructorCall) srcNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
					    srcCallMethodName=namespace[namespace.length-1];
				     }
				
				     String dstCallMethodName;
				     CtElement dst = dstNode;

				     CtTargetedExpression dstInvocation = null;
				     if (dstNode instanceof CtInvocation) {
					    dstCallMethodName = ((CtInvocation) dstNode).getExecutable().getSimpleName();
					    dstInvocation = (CtTargetedExpression) dstNode;
				    } else {		
					    String[] namespace=((CtConstructorCall) dstNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
					    dstCallMethodName=namespace[namespace.length-1];	
					    dstInvocation = (CtTargetedExpression) dstNode;
				    }
					
					if (!whethermethoddefupdated(srcNode, dstNode)) {
						
						if (srcInvocation != null && dstInvocation != null && srcInvocation.getTarget() != null
								&& dstInvocation.getTarget() != null
								&& !srcInvocation.getTarget().equals(dstInvocation.getTarget())) {
							continue;
						}

						CtElement parentLine = MappingAnalysis.getParentLine(new LineFilter(), srcInvocation);
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parentLine);
                        
						if (!srcCallMethodName.equals(dstCallMethodName)) {
							repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
									new PatternInstance(WRONG_METHOD_REF, operation, dst, srcInvocation, parentLine,
											lineTree, new PropertyPair("Change", "differentMethodName")));

						} else {
							repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
										new PatternInstance(WRONG_METHOD_REF, operation, dst, srcInvocation, parentLine,
												lineTree, new PropertyPair("Change", "SameNamedifferentArgument")));
						}
						
						List<CtExpression> invocationArgumentsold = new ArrayList<>();
						if (srcNode instanceof CtInvocation) {
							invocationArgumentsold = ((CtInvocation) srcNode).getArguments();
						}
						if (srcNode instanceof CtConstructorCall) {
							invocationArgumentsold = ((CtConstructorCall) srcNode).getArguments();
						}
						
						List<CtExpression> invocationArgumentnew = new ArrayList<>();
						if (dstNode instanceof CtInvocation) {
							invocationArgumentnew = ((CtInvocation) dstNode).getArguments();
						}
						if (dstNode instanceof CtConstructorCall) {
							invocationArgumentnew = ((CtConstructorCall) dstNode).getArguments();
						}
						
						detectVarArgumentChange(invocationArgumentsold, invocationArgumentnew, repairPatterns, srcNode, dstNode);
					}
				}
			}
		}
	}
	
	public void detectVarArgumentChange(List<CtExpression> invocationArgumentsold, List<CtExpression> invocationArgumentsnew, 
			RepairPatterns repairPatterns, CtElement source, CtElement destination) {
		
		for (Operation operation : diff.getAllOperations()) {
			this.detectWrapsMethod(invocationArgumentsold, invocationArgumentsnew, operation, repairPatterns, source, destination);
			this.detecfWrapIfChange(invocationArgumentsold, invocationArgumentsnew, operation, repairPatterns, source, destination);
		}
		
		this.detectConstantChange(invocationArgumentsold,invocationArgumentsnew,repairPatterns, source, destination);
		this.detectNullCheckChange(invocationArgumentsold,invocationArgumentsnew,repairPatterns, source, destination);
		
		List<CtElement> alreadyconsidered = new ArrayList<CtElement>();
		for (int i = 0; i < diff.getAllOperations().size(); i++) {

			Operation operation = diff.getAllOperations().get(i);
			if (operation instanceof DeleteOperation) {
				Operation operationDelete = operation;
				CtElement srcNode = operationDelete.getSrcNode();

				if ((srcNode instanceof CtVariableAccess || srcNode instanceof CtInvocation ||
						srcNode instanceof CtConstructorCall || srcNode instanceof CtTypeAccess) && invocationArgumentsold.contains(srcNode)
						&&  srcNode.getParent()==source) { 

					CtElement newElementReplacementOfTheVar = null;
					boolean wasVariableWrapped = false;
					for (int j = 0; j < diff.getAllOperations().size(); j++) {
						Operation operation2 = diff.getAllOperations().get(j);
						if (operation2 instanceof InsertOperation) {
							CtElement node2 = operation2.getSrcNode();
							
							if ((node2 instanceof CtInvocation || node2 instanceof CtConstructorCall) && 
									invocationArgumentsnew.contains(node2) && node2.getParent()==destination) {
								wasVariableWrapped = whethervarwrapped(srcNode, node2);
							}
						 }
				    }
					
					for (int j = 0; j < diff.getAllOperations().size(); j++) {
						Operation operation2 = diff.getAllOperations().get(j);
						if (operation2 instanceof InsertOperation) {
							CtElement node2 = operation2.getSrcNode();
									
							if (invocationArgumentsnew.contains(node2) && node2.getParent()==destination &&
									!alreadyconsidered.contains(node2)) {
									newElementReplacementOfTheVar = node2;
									alreadyconsidered.add(node2);
									break;
							}
						 }
				    }
							
					if (!wasVariableWrapped) {

						CtElement susp = operationDelete.getSrcNode();
						CtElement patch = null;

						ITree parentRight = MappingAnalysis.getParentInRight(diff, operationDelete.getAction());
						if (parentRight != null) {
							patch = (CtElement) parentRight.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);
						}

						CtElement parentLine = MappingAnalysis.getParentLine(new LineFilter(), susp);
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parentLine);

						PropertyPair[] metadata = null;
						PropertyPair propertyOldElemet = new PropertyPair("Old", "Removed_"
										+ srcNode.getClass().getSimpleName().replace("Ct", "").replace("Impl", ""));

						String replaceElementType="";

						if (newElementReplacementOfTheVar != null) {
							 PropertyPair propertyNewElement = new PropertyPair("New",
										"Added_" + newElementReplacementOfTheVar.getClass().getSimpleName()
													.replace("Ct", "").replace("Impl", ""));
							 replaceElementType=newElementReplacementOfTheVar.getClass().getSimpleName()
											.replace("Ct", "").replace("Impl", "");

							 metadata = new PropertyPair[] { propertyOldElemet, propertyNewElement };
						 } else
							 metadata = new PropertyPair[] { propertyOldElemet };

						 Boolean whetherConsiderInitial=false;
								
						 if(metadata.length==2) {
							 
							 if(newElementReplacementOfTheVar instanceof CtFieldRead) {
									
									if(whethervariablenewlydeclared((CtFieldRead)newElementReplacementOfTheVar))
										continue;
							  }
									
                             if(newElementReplacementOfTheVar instanceof CtFieldWrite) {
									
									if(whethervariablenewlydeclared((CtFieldWrite)newElementReplacementOfTheVar))
										continue;
							  }
                             
                             if(newElementReplacementOfTheVar instanceof CtVariableRead) {
									
									if(whethervariablenewlydeclared((CtVariableRead)newElementReplacementOfTheVar))
										continue;
							 }
									
                             if(newElementReplacementOfTheVar instanceof CtVariableWrite) {
									
									if(whethervariablenewlydeclared((CtVariableWrite)newElementReplacementOfTheVar))
										continue;
							 }
                             
							if(replaceElementType.equals("Invocation")||replaceElementType.equals("VariableRead")
											||replaceElementType.equals("FieldRead")||replaceElementType.equals("ConstructorCall")
											||replaceElementType.equals("Literal")||replaceElementType.equals("FieldWrite")
											||replaceElementType.equals("VariableWrite")||replaceElementType.equals("TypeAccess"))
								whetherConsiderInitial=true;
						  }					  
						  
						  if(whetherConsiderInitial) {

							   if (srcNode instanceof CtInvocation) {
								   
								   if(newElementReplacementOfTheVar instanceof CtInvocation &&  
										((CtInvocation)srcNode).getExecutable().getSimpleName().
										equals(((CtInvocation)newElementReplacementOfTheVar).getExecutable().getSimpleName())
										&&((CtInvocation) srcNode).getArguments().size()==((CtInvocation)newElementReplacementOfTheVar).getArguments().size())
								   { }
								   else {
									   if(newElementReplacementOfTheVar instanceof CtInvocation) {
										   
										   String srcCallMethodName=((CtInvocation)srcNode).getExecutable().getSimpleName();
										   String dstCallMethodName=((CtInvocation)newElementReplacementOfTheVar).
												   getExecutable().getSimpleName();
										   
										   if (!srcCallMethodName.equals(dstCallMethodName)) {
												repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
														new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
																lineTree, new PropertyPair("Change", "differentMethodName")));

											} else {					
												repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
														new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
															lineTree,new PropertyPair("Change", "SameNamedifferentArgument")));
											}
									   }
									   else
									        repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF, new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp,
											     parentLine, lineTree, metadata));
								   }
							    }
							  else  if (srcNode instanceof CtConstructorCall) {
								  
							   String[] namespace=((CtConstructorCall) srcNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
							   String originalconstructorname =namespace [namespace.length-1];	
							   
							   String[] namespacenew=((CtConstructorCall) newElementReplacementOfTheVar).getExecutable().getSignature().split("\\(")[0].split("\\.");
							   String newconstructorname =namespacenew [namespacenew.length-1];	
									
							   if(newElementReplacementOfTheVar instanceof CtConstructorCall &&  
									originalconstructorname.equals(newconstructorname)
									&&((CtConstructorCall) srcNode).getArguments().size()==((CtConstructorCall)newElementReplacementOfTheVar).getArguments().size())
							   { }
							   else  { 
								   if(newElementReplacementOfTheVar instanceof CtConstructorCall) {
									   
									   if (!originalconstructorname.equals(newconstructorname)) {
											repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
													new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
															lineTree, new PropertyPair("Change", "differentMethodName")));
										} else {					
											repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
													new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp, parentLine,
														lineTree,new PropertyPair("Change", "SameNamedifferentArgument")));
										}
								   }
								   else
								      repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
								         new PatternInstance(WRONG_METHOD_REF, operationDelete, patch, susp,
										 parentLine, lineTree, metadata));
							   }
						    }
							  else
								  repairPatterns.incrementFeatureCounterInstance(WRONG_VAR_REF,
										new PatternInstance(WRONG_VAR_REF, operationDelete, patch, susp, parentLine,
												lineTree, metadata));
						    }	 
					  }
				 } 
			}
			
			if (operation instanceof UpdateOperation) {
				CtElement srcNode = operation.getSrcNode();
				CtElement dstNode = operation.getDstNode();

				if(!invocationArgumentsold.contains(srcNode) || srcNode.getParent()!= source
						|| !invocationArgumentsnew.contains(dstNode) || dstNode.getParent()!=destination)
					continue;
				
				if (srcNode instanceof CtVariableAccess ||srcNode instanceof CtTypeAccess) {
					if (operation.getDstNode() instanceof CtVariableAccess
							|| operation.getDstNode() instanceof CtTypeAccess
						           ) {
						
						if(srcNode instanceof CtVariableAccess && operation.getDstNode() instanceof CtVariableAccess) {

							if(whethervariableDeclarationUpdated((CtVariableAccess)srcNode, (CtVariableAccess)operation.getDstNode()))
								continue;
							
							if(whethervariablenewlydeclared((CtVariableAccess)operation.getDstNode()))
								continue;
						}	

						CtElement susp = operation.getSrcNode();
						CtElement patch = null;

						CtElement parentLine = MappingAnalysis.getParentLine(new LineFilter(), susp);
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parentLine);

						ITree parentRight = MappingAnalysis.getParentInRight(diff, operation.getAction());
						if (parentRight != null) {
							patch = (CtElement) parentRight.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);
						}

						repairPatterns.incrementFeatureCounterInstance(WRONG_VAR_REF, new PatternInstance(WRONG_VAR_REF,
								operation, patch, susp, parentLine, lineTree, new PropertyPair("Change", "Update_"
										+ srcNode.getClass().getSimpleName().replace("Ct", "").replace("Impl", ""))));
					}
				}
				
				if (!(srcNode instanceof CtInvocation) && !(srcNode instanceof CtConstructorCall)) {
					continue;
				}

				if (dstNode instanceof CtInvocation || dstNode instanceof CtConstructorCall) {

					 String srcCallMethodName;
				     CtTargetedExpression srcInvocation = (CtTargetedExpression) srcNode;
				
				     if(srcNode instanceof CtInvocation) {
					    srcCallMethodName = ((CtInvocation) srcNode).getExecutable().getSimpleName();
				     } else {	
					    String[] namespace=((CtConstructorCall) srcNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
					    srcCallMethodName=namespace[namespace.length-1];
				     }
				
				     String dstCallMethodName;
				     CtElement dst = dstNode;

				     CtTargetedExpression dstInvocation = null;
				     if (dstNode instanceof CtInvocation) {
					    dstCallMethodName = ((CtInvocation) dstNode).getExecutable().getSimpleName();
					    dstInvocation = (CtTargetedExpression) dstNode;
				    } else {		
					    String[] namespace=((CtConstructorCall) dstNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
					    dstCallMethodName=namespace[namespace.length-1];	
					    dstInvocation = (CtTargetedExpression) dstNode;
				    }

					if (!whethermethoddefupdated(srcNode, dstNode)) {

						if (srcInvocation != null && dstInvocation != null && srcInvocation.getTarget() != null
								&& dstInvocation.getTarget() != null
								&& !srcInvocation.getTarget().equals(dstInvocation.getTarget())) {
							continue;
						}

						CtElement parentLine = MappingAnalysis.getParentLine(new LineFilter(), srcInvocation);
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parentLine);

						if (!srcCallMethodName.equals(dstCallMethodName)) {
							repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
									new PatternInstance(WRONG_METHOD_REF, operation, dst, srcInvocation, parentLine,
											lineTree, new PropertyPair("Change", "differentMethodName")));
						} else {
							   repairPatterns.incrementFeatureCounterInstance(WRONG_METHOD_REF,
										new PatternInstance(WRONG_METHOD_REF, operation, dst, srcInvocation, parentLine,
											lineTree, new PropertyPair("Change", "SameNamedifferentArgument")));
						}
					}
				}
			}
		}
	}
	
	private void detectWrapsMethod(List<CtExpression> invocationArgumentsold, List<CtExpression> invocationArgumentsnew, 
			Operation operation, RepairPatterns repairPatterns, CtElement source, CtElement destination) {
		
		if (operation.getSrcNode() instanceof CtInvocation) {
			if (operation instanceof InsertOperation) {
				CtInvocation ctInvocation = (CtInvocation) operation.getSrcNode();
				List<CtExpression> invocationArguments = ctInvocation.getArguments();
				if(invocationArgumentsnew.contains(ctInvocation) && ctInvocation.getParent()==destination) {
				
				  for (Operation operation2 : diff.getAllOperations()) {
					 if (operation2 instanceof DeleteOperation) {
						CtElement elementRemoved = operation2.getSrcNode();
						
						if (elementRemoved instanceof CtVariableRead && invocationArgumentsold.contains(elementRemoved)
								&& elementRemoved.getParent()==source) {

							if (invocationArguments.contains(elementRemoved)) {
								CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(), elementRemoved);
								ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);
								repairPatterns.incrementFeatureCounterInstance(WRAPS_METHOD,
										new PatternInstance(WRAPS_METHOD, operation, ctInvocation, elementRemoved,
												lineP, lineTree, new PropertyPair("Old", "VarRead"),
												new PropertyPair("New", "Invocation")));
							}
						}
					 }
				  }

				 for(CtExpression ctExpression : invocationArguments) {
					if (ctExpression.getMetadata("isMoved") != null) {
						
						List<CtElement> suspLeft = MappingAnalysis.getTreeLeftMovedFromRight(diff, ctInvocation);
						if (suspLeft == null || suspLeft.isEmpty())
							return;

						CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(), suspLeft.get(0));
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);
						
						if(RepairPatternUtils.getIsInvocationInStatemnt(diff, lineP, ctInvocation) &&
							invocationArgumentsold.contains(ctExpression) && ctExpression.getParent()==source) {
								
							if((RepairPatternUtils.getElementInOld(diff, ctExpression).getParent() instanceof CtConstructorCall ||
									  RepairPatternUtils.getElementInOld(diff, ctExpression).getParent() instanceof CtInvocation)
									  && RepairPatternUtils.getElementInOld(diff, ctExpression).getParent()!=
									  RepairPatternUtils.getElementInOld(diff, ctInvocation.getParent())) {
							  }
							else
						    repairPatterns.incrementFeatureCounterInstance(WRAPS_METHOD,
								new PatternInstance(WRAPS_METHOD, operation, ctInvocation, ctExpression, lineP,
										lineTree, new PropertyPair("Old", "MovedExpression"),
										new PropertyPair("New", "Invocation")));
						}
					}
				}
			  }
			} else {
				if (operation instanceof DeleteOperation) {
					CtInvocation ctInvocation = (CtInvocation) operation.getSrcNode();
					CtStatement statementParent = ctInvocation.getParent(new TypeFilter<>(CtStatement.class));
					
					if(invocationArgumentsold.contains(ctInvocation) && ctInvocation.getParent()==source)
					  if (statementParent.getMetadata("delete") == null) {
						List<CtExpression> invocationArguments = ctInvocation.getArguments();

						for (CtExpression ctExpression : invocationArguments) {
							if (ctExpression.getMetadata("isMoved") != null
									&& ctExpression.getMetadata("movingSrc") != null) {

								if(RepairPatternUtils.getIsMovedExpressionInStatemnt(diff, statementParent, ctExpression) &&
										invocationArgumentsnew.contains(ctExpression)&& ctExpression.getParent()==destination)
								{
								   CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(),
										operation.getSrcNode());
								   ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);

								   repairPatterns.incrementFeatureCounterInstance(UNWRAP_METHOD, new PatternInstance(
										UNWRAP_METHOD, operation, statementParent, ctInvocation, lineP, lineTree));
								}
							}
						}
					}
				}
			}
		}
		
		if (operation.getSrcNode() instanceof CtConstructorCall) {
			if (operation instanceof InsertOperation) {
				CtConstructorCall ctInvocation = (CtConstructorCall) operation.getSrcNode();
				List<CtExpression> invocationArguments = ctInvocation.getArguments();
				if(invocationArgumentsnew.contains(ctInvocation) && ctInvocation.getParent()==destination) {
				  for (Operation operation2 : diff.getAllOperations()) {
					 if (operation2 instanceof DeleteOperation) {
						CtElement elementRemoved = operation2.getSrcNode();
						
						if (elementRemoved instanceof CtVariableRead && invocationArgumentsold.contains(elementRemoved)
								&& elementRemoved.getParent()==source) {

							if (invocationArguments.contains(elementRemoved)) {
								CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(), elementRemoved);
								ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);
								repairPatterns.incrementFeatureCounterInstance(WRAPS_METHOD,
										new PatternInstance(WRAPS_METHOD, operation, ctInvocation, elementRemoved,
												lineP, lineTree, new PropertyPair("Old", "VarRead"),
												new PropertyPair("New", "Constructor")));
							}
						}
					 }
				  }

				 for(CtExpression ctExpression : invocationArguments) {
					if (ctExpression.getMetadata("isMoved") != null) {
						
						List<CtElement> suspLeft = MappingAnalysis.getTreeLeftMovedFromRight(diff, ctInvocation);
						if (suspLeft == null || suspLeft.isEmpty())
							return;

						CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(), suspLeft.get(0));
						ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);
							
						if(RepairPatternUtils.getIsInvocationInStatemnt(diff, lineP, ctInvocation) &&
						   invocationArgumentsold.contains(ctExpression) && ctExpression.getParent()==source) {

							 if((RepairPatternUtils.getElementInOld(diff, ctExpression).getParent() instanceof CtConstructorCall ||
									  RepairPatternUtils.getElementInOld(diff, ctExpression).getParent() instanceof CtInvocation)
									  && RepairPatternUtils.getElementInOld(diff, ctExpression).getParent()!=
									  RepairPatternUtils.getElementInOld(diff, ctInvocation.getParent())) {
							}
						     else
						     repairPatterns.incrementFeatureCounterInstance(WRAPS_METHOD,
								new PatternInstance(WRAPS_METHOD, operation, ctInvocation, ctExpression, lineP,
										lineTree, new PropertyPair("Old", "MovedExpression"),
										new PropertyPair("New", "Constructor")));
						}
					}
				}
			  }
			} else {
				if (operation instanceof DeleteOperation) {
					CtConstructorCall ctInvocation = (CtConstructorCall) operation.getSrcNode();
					CtStatement statementParent = ctInvocation.getParent(new TypeFilter<>(CtStatement.class));

					if(invocationArgumentsold.contains(ctInvocation) && ctInvocation.getParent()==source)
					  if (statementParent.getMetadata("delete") == null) {
						List<CtExpression> invocationArguments = ctInvocation.getArguments();

						for (CtExpression ctExpression : invocationArguments) {
							if (ctExpression.getMetadata("isMoved") != null
									&& ctExpression.getMetadata("movingSrc") != null) {

								if(RepairPatternUtils.getIsMovedExpressionInStatemnt(diff, statementParent, ctExpression) &&
										invocationArgumentsnew.contains(ctExpression) &&
										ctExpression.getParent()==destination)
								{
								   CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(),
										operation.getSrcNode());
								   ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);

								   repairPatterns.incrementFeatureCounterInstance(UNWRAP_METHOD, new PatternInstance(
										UNWRAP_METHOD, operation, statementParent, ctInvocation, lineP, lineTree));
								}
							}
						}
					}
				}
			}
		}
	}
	
	public void detectConstantChange(List<CtExpression> invocationArgumentsold, List<CtExpression> invocationArgumentsnew, 
			 RepairPatterns repairPatterns, CtElement source, CtElement destination) {
		List<CtElement> alreadyconsidered = new ArrayList<CtElement>();
		for (int i = 0; i < diff.getAllOperations().size(); i++) {
			Operation operation = diff.getAllOperations().get(i);

			if ((operation instanceof UpdateOperation) && invocationArgumentsold.contains(operation.getSrcNode()) &&
					operation.getSrcNode().getParent()==source && operation.getDstNode().getParent()==destination
					&& invocationArgumentsnew.contains(operation.getDstNode())) {
				CtElement srcNode = operation.getSrcNode();
				
				CtElement parent = MappingAnalysis.getParentLine(new LineFilter(), srcNode);
				ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parent);

				if (srcNode instanceof CtLiteral) {
					repairPatterns.incrementFeatureCounterInstance(CONST_CHANGE,
							new PatternInstance(CONST_CHANGE, operation, operation.getDstNode(), srcNode, parent,
									lineTree, new PropertyPair("type", "literal")));
				}
				
			} else {			

				if (operation instanceof DeleteOperation && operation.getSrcNode() instanceof CtLiteral &&
						invocationArgumentsold.contains(operation.getSrcNode()) && operation.getSrcNode().getParent()==source) {

					CtLiteral ctLiteral = (CtLiteral) operation.getSrcNode();
					
					for (int j = 0; j < diff.getAllOperations().size(); j++) {
						Operation operation2Insert = diff.getAllOperations().get(j);
						if (operation2Insert instanceof InsertOperation && 
								invocationArgumentsnew.contains(operation2Insert.getSrcNode()) &&
								operation2Insert.getSrcNode().getParent()==destination) {
							CtElement ctElement = operation2Insert.getSrcNode();

							boolean isConstantVariable = false;
							if (ctElement instanceof CtVariableAccess || ctElement instanceof CtArrayAccess 
									|| (ctElement instanceof CtTypeAccess && !RepairPatternUtils.isThisAccess((CtTypeAccess) ctElement)
										&& RepairPatternUtils.isConstantTypeAccess((CtTypeAccess) ctElement))) {
								isConstantVariable = true;
							}
							if (isConstantVariable && !alreadyconsidered.contains(ctElement)) {
								alreadyconsidered.add(ctElement);
								CtElement parent = MappingAnalysis.getParentLine(new LineFilter(), ctLiteral);
								ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(parent);

								repairPatterns.incrementFeatureCounterInstance(CONST_CHANGE,
										new PatternInstance(CONST_CHANGE, operation2Insert,
												operation2Insert.getSrcNode(), ctLiteral, parent, lineTree,
												new PropertyPair("type", "literal_by_varaccess")));
								break;
							}
						}
					}
				}
			}
		}
	}
	
	public void detecfWrapIfChange(List<CtExpression> invocationArgumentsold, List<CtExpression> invocationArgumentsnew, 
			Operation operation, RepairPatterns repairPatterns, CtElement source, CtElement destination) {

		if (operation instanceof InsertOperation || operation instanceof DeleteOperation) {
			CtElement ctElement = operation.getSrcNode();
			SpoonHelper.printInsertOrDeleteOperation(ctElement.getFactory().getEnvironment(), ctElement, operation);

			List<CtConditional> conditionalList = ctElement.getElements(new TypeFilter<>(CtConditional.class));
			for (CtConditional ctConditional : conditionalList) {
				if (ctConditional.getMetadata("new") != null && (invocationArgumentsold.contains(ctConditional)||
						invocationArgumentsnew.contains(ctConditional))) {
					
					CtExpression thenExpression = ctConditional.getThenExpression();
					CtExpression elseExpression = ctConditional.getElseExpression();
					
					if (thenExpression.getMetadata("new") == null || elseExpression.getMetadata("new") == null) {
						CtElement statementParent = ctConditional.getParent(new TypeFilter<>(CtStatement.class));
						if (operation instanceof InsertOperation) {

							if (statementParent.getMetadata("new") == null) {
								// We get the not new (the moved).
								// As the then/else is inserted with the Condition, it's always in the right
								CtElement suspRigh = (thenExpression.getMetadata("new") != null) ? elseExpression
										: thenExpression;

								ITree leftMoved = MappingAnalysis.getLeftFromRightNodeMapped(diff,
										(ITree) suspRigh.getMetadata("gtnode"));

								CtElement susp = (CtElement) leftMoved.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);

								CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(), susp);
								ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);

								if(invocationArgumentsnew.contains(ctConditional) && ctConditional.getParent()==destination
										&& invocationArgumentsold.contains(susp) && susp.getParent()==source)
								   repairPatterns.incrementFeatureCounterInstance(WRAPS_IF_ELSE, new PatternInstance(
										WRAPS_IF_ELSE, operation, ctConditional, susp, lineP, lineTree, 
										new PropertyPair("case", "elsenotnull")));
							}
						} else {
							if (statementParent.getMetadata("delete") == null) {
								List susps = new ArrayList();
								CtExpression patch = null;
								if (thenExpression.getMetadata("new") == null) {
									patch = thenExpression;
									susps.add(elseExpression);
									susps.add(ctConditional.getCondition());

								} else {
									patch = elseExpression;
									susps.add(thenExpression);
									susps.add(ctConditional.getCondition());
								}
								CtElement lineP = MappingAnalysis.getParentLine(new LineFilter(),
										(CtElement) susps.get(0));
								ITree lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);
								
								ITree rightMoved = MappingAnalysis.getRightFromLeftNodeMapped(diff,
										(ITree) patch.getMetadata("gtnode"));

								CtElement remaining = (CtElement) rightMoved.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);

								if(invocationArgumentsnew.contains(remaining) && remaining.getParent()==destination
										&& invocationArgumentsold.contains(ctConditional) && ctConditional.getParent()==source)
								  repairPatterns.incrementFeatureCounterInstance(UNWRAP_IF_ELSE,
										new PatternInstance(UNWRAP_IF_ELSE, operation, patch, susps, lineP, lineTree));
							}
						}
					} 
				}
			}
		}
	}
	
	public void detectNullCheckChange(List<CtExpression> invocationArgumentsold, List<CtExpression> invocationArgumentsnew, 
			 RepairPatterns repairPatterns, CtElement source, CtElement destination) {

		for (Operation operation : diff.getAllOperations()) {
			if (operation instanceof InsertOperation) {

				CtElement srcNode = operation.getSrcNode();

				if (srcNode instanceof spoon.reflect.declaration.CtMethod || !invocationArgumentsnew.contains(srcNode)
						|| srcNode.getParent()!=destination)
					continue;

				SpoonHelper.printInsertOrDeleteOperation(srcNode.getFactory().getEnvironment(), srcNode, operation);

				List<CtBinaryOperator> binaryOperatorList = srcNode.getElements(new NullCheckFilter());
				
				for (CtBinaryOperator binaryOperator : binaryOperatorList) {
					if (RepairPatternUtils.isNewBinaryOperator(binaryOperator)) {
						if (RepairPatternUtils.isNewConditionInBinaryOperator(binaryOperator)) {

							final CtElement referenceExpression;
							if (binaryOperator.getRightHandOperand().toString().equals("null")) {
								referenceExpression = binaryOperator.getLeftHandOperand();
							} else {
								referenceExpression = binaryOperator.getRightHandOperand();
							}

							boolean wasPatternFound = false;

							List soldt = null;
							List soldelse = null;
							
							CtElement parent = binaryOperator.getParent(new LineFilter());

							if (binaryOperator.getParent() instanceof CtConditional) {
								
								CtConditional c = (CtConditional) binaryOperator.getParent();
								CtElement thenExpr = c.getThenExpression();
								CtElement elseExp = c.getElseExpression();

								if (thenExpr != null) {
									soldt = new ArrayList<>();
									// If it's not new the THEN
									if (thenExpr.getMetadata("new") == null && invocationArgumentsold.
											contains(RepairPatternUtils.getElementInOld(diff, thenExpr))
											&& RepairPatternUtils.getElementInOld(diff, thenExpr).getParent()==source) {
									//	soldelse.add(thenExpr);
										soldt.add(RepairPatternUtils.getElementInOld(diff, thenExpr));
										wasPatternFound = true;
									}
								}
								if (elseExp != null) {
									soldelse = new ArrayList<>();
									
									if (elseExp.getMetadata("new") == null && invocationArgumentsold.
											contains(RepairPatternUtils.getElementInOld(diff, elseExp))
											&& RepairPatternUtils.getElementInOld(diff, elseExp).getParent()==source) {
									//	soldelse.add(elseExp);
										soldt.add(RepairPatternUtils.getElementInOld(diff, elseExp));
										wasPatternFound = true;
									}
								}
							}

							if (wasPatternFound) {

								List<CtElement> susp = new ArrayList<>();
								if (soldt != null)
									susp.addAll(soldt);
								if (soldelse != null)
									susp.addAll(soldelse);

								CtElement lineP = null;
								ITree lineTree = null;
								if (!susp.isEmpty()) {

									lineP = MappingAnalysis.getParentLine(new LineFilter(), (CtElement) susp.get(0));
									lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);

								} else {

									InsertOperation operationIns = (InsertOperation) operation;

									List<ITree> treeInLeft = MappingAnalysis.getFollowStatementsInLeft(diff,
											operationIns.getAction());

									if (treeInLeft.isEmpty()) {
										System.out.println(
												"Problem!!!! Empty parent in " + MISS_NULL_CHECK_N.toLowerCase());
										continue;
									}

									for (ITree iTree : treeInLeft) {
										susp.add((CtElement) iTree.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT));
									}

									lineP = susp.get(0);
								//	lineTree = treeInLeft.get(0);
									lineTree = MappingAnalysis.getFormatedTreeFromControlFlow(lineP);
								}

								if (binaryOperator.getKind().equals(BinaryOperatorKind.EQ)) {
									repairPatterns.incrementFeatureCounterInstance(MISS_NULL_CHECK_P,
											new PatternInstance(MISS_NULL_CHECK_P, operation, parent, susp, lineP,
													lineTree));
								} else {
									repairPatterns.incrementFeatureCounterInstance(MISS_NULL_CHECK_N,
											new PatternInstance(MISS_NULL_CHECK_N, operation, parent, susp, lineP,
													lineTree));
								}
							}
						}
					}
				}
			}
		}
	}
	
	public boolean whethermethoddefupdated(CtElement srcNode, CtElement dstNode) {
		
		 boolean wasMethodDefUpdated = false;

	     String srcCallMethodName;
	     CtTargetedExpression srcInvocation = (CtTargetedExpression) srcNode;
	     List<CtTypeReference> srcCallArguments;
	     List<CtTypeReference> srcCallArgumentsFromExec;
	     List<CtTypeReference> srcCallRealArguments;
	
	     if(srcNode instanceof CtInvocation) {
		    srcCallMethodName = ((CtInvocation) srcNode).getExecutable().getSimpleName();
		    srcCallArgumentsFromExec = ((CtInvocation) srcNode).getExecutable().getParameters();
		    srcCallArguments = ((CtInvocation) srcNode).getActualTypeArguments();
		    srcCallRealArguments = ((CtInvocation) srcNode).getArguments();
	     } else {	
		    String[] namespace=((CtConstructorCall) srcNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
		    srcCallMethodName=namespace[namespace.length-1];
		    srcCallArguments = ((CtConstructorCall) srcNode).getActualTypeArguments();
		    srcCallRealArguments = ((CtConstructorCall) srcNode).getArguments();
		    srcCallArgumentsFromExec = ((CtConstructorCall) srcNode).getExecutable().getParameters();
	     }
	
	     String dstCallMethodName;
	     CtElement dst = dstNode;
	     List<CtTypeReference> dstCallArguments;
	     List<CtTypeReference> dstCallArgumentsFromExec;
	     List<CtTypeReference> dstCallRealArguments;

	     CtTargetedExpression dstInvocation = null;
	     if (dstNode instanceof CtInvocation) {
		    dstCallMethodName = ((CtInvocation) dstNode).getExecutable().getSimpleName();
		    dstCallArguments = ((CtInvocation) dstNode).getActualTypeArguments();
		    dstCallArgumentsFromExec = ((CtInvocation) dstNode).getExecutable().getParameters();
		    dstInvocation = (CtTargetedExpression) dstNode;
		    dstCallRealArguments = ((CtInvocation) dstNode).getArguments();
	    } else {		
		    String[] namespace=((CtConstructorCall) dstNode).getExecutable().getSignature().split("\\(")[0].split("\\.");
		    dstCallMethodName=namespace[namespace.length-1];	
		    dstCallArguments = ((CtConstructorCall) dstNode).getActualTypeArguments();
		    dstCallRealArguments = ((CtConstructorCall) dstNode).getArguments();
		    dstCallArgumentsFromExec = ((CtConstructorCall) dstNode).getExecutable().getParameters();
		    dstInvocation = (CtTargetedExpression) dstNode;
	    }

	    for (Operation operation2 : operations) {
		   if (operation2 instanceof InsertOperation) {
			   CtElement insertedNode = operation2.getSrcNode();
			   if (insertedNode instanceof CtParameter) {
				  CtElement ctElement = ((InsertOperation) operation2).getParent();
				  if (ctElement instanceof CtMethod) {
					   CtMethod oldMethod = (CtMethod) ctElement;
					   CtMethod newMethod = insertedNode.getParent(CtMethod.class);

					   if (oldMethod.getSimpleName().equals(srcCallMethodName)
							&& newMethod.getSimpleName().equals(dstCallMethodName)) {
						    boolean oldParEquals = true;
						    List<CtParameter> oldMethodPars = oldMethod.getParameters();
						    for (int k = 0; k < oldMethodPars.size(); k++) {
							   CtTypeReference methodParType = oldMethodPars.get(k).getType();
							   if (k < srcCallArguments.size()) {
								  CtTypeReference methodCallArgType = srcCallArguments.get(k);
								  if (!methodParType.getQualifiedName()
										.equals(methodCallArgType.getQualifiedName())) {
									oldParEquals = false;
									break;
								  }
							   }
						    }
						    
						    if (oldParEquals) {
							   boolean newParEquals = true;
							   List<CtParameter> newMethodPars = newMethod.getParameters();
							   for (int k = 0; k < newMethodPars.size(); k++) {
								  CtTypeReference methodParType = newMethodPars.get(k).getType();
								  if (k < dstCallArguments.size()) {
									 CtTypeReference methodCallArgType = dstCallArguments.get(k);
									 if (!methodParType.getQualifiedName()
											.equals(methodCallArgType.getQualifiedName())) {
										newParEquals = false;
										break;
									 }
								  }
							    } 
							   
							   if (newParEquals) {
								wasMethodDefUpdated = true;
							   }
						   }
					  }
				  }
				
				  if(ctElement instanceof CtConstructor) {
					  CtConstructor oldConstructor= (CtConstructor) ctElement;
					  CtConstructor newConstructor= insertedNode.getParent(CtConstructor.class);

					  if (oldConstructor.getSimpleName().equals(srcCallMethodName)
							&& newConstructor.getSimpleName().equals(dstCallMethodName)) {
						  boolean oldParEquals = true;
						  List<CtParameter> oldMethodPars = oldConstructor.getParameters();
						  for (int k = 0; k < oldMethodPars.size(); k++) {
							  CtTypeReference methodParType = oldMethodPars.get(k).getType();
							  if (k < srcCallArguments.size()) {
								 CtTypeReference methodCallArgType = srcCallArguments.get(k);
								 if (!methodParType.getQualifiedName()
										.equals(methodCallArgType.getQualifiedName())) {
									oldParEquals = false;
									break;
								 }
							  }
						   }
						  
						   if (oldParEquals) {
							   boolean newParEquals = true;
							   List<CtParameter> newMethodPars = newConstructor.getParameters();
						  	   for (int k = 0; k < newMethodPars.size(); k++) {
								  CtTypeReference methodParType = newMethodPars.get(k).getType();
								  if (k < dstCallArguments.size()) {
									 CtTypeReference methodCallArgType = dstCallArguments.get(k);
									 if (!methodParType.getQualifiedName()
											.equals(methodCallArgType.getQualifiedName())) {
										newParEquals = false;
										break;
									 }
								  }
							} 
						  	   
							if (newParEquals) {
								wasMethodDefUpdated = true;
							}
						}
					}
				}
			}
		}
		
		if (operation2 instanceof DeleteOperation) {
			
			CtElement deleteNode = operation2.getSrcNode();
			if (deleteNode instanceof CtParameter) {
				CtElement ctElement = deleteNode.getParent(CtMethod.class);
				if (ctElement instanceof CtMethod) {
					CtMethod oldMethod = (CtMethod) ctElement;
					CtMethod newMethod;
					
					ITree rightTreemethod= MappingAnalysis.getRightFromLeftNodeMapped(diff, oldMethod); 

					if(rightTreemethod==null)
						newMethod=null;
					else newMethod = (CtMethod) rightTreemethod.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);

					if (oldMethod.getSimpleName().equals(srcCallMethodName)
							&& newMethod.getSimpleName().equals(dstCallMethodName)) {
						boolean oldParEquals = true;
						List<CtParameter> oldMethodPars = oldMethod.getParameters();
						for (int k = 0; k < oldMethodPars.size(); k++) {
							CtTypeReference methodParType = oldMethodPars.get(k).getType();
							if (k < srcCallArguments.size()) {
								CtTypeReference methodCallArgType = srcCallArguments.get(k);
								if (!methodParType.getQualifiedName()
										.equals(methodCallArgType.getQualifiedName())) {
									oldParEquals = false;
									break;
								}
							}
						}
						
						if (oldParEquals) {
							boolean newParEquals = true;
							List<CtParameter> newMethodPars = newMethod.getParameters();
							for (int k = 0; k < newMethodPars.size(); k++) {
								CtTypeReference methodParType = newMethodPars.get(k).getType();
								if (k < dstCallArguments.size()) {
									CtTypeReference methodCallArgType = dstCallArguments.get(k);
									if (!methodParType.getQualifiedName()
											.equals(methodCallArgType.getQualifiedName())) {
										newParEquals = false;
										break;
									}
								}
							} 

							if (newParEquals) {
								wasMethodDefUpdated = true;
							}
						}
					}
				}
				
				ctElement = deleteNode.getParent(CtConstructor.class);
				if (ctElement instanceof CtConstructor) {
					CtConstructor oldConstructor = (CtConstructor) ctElement;
					CtConstructor newConstructor;
					
					ITree rightTreemethod= MappingAnalysis.getRightFromLeftNodeMapped(diff, oldConstructor); 

					if(rightTreemethod==null)
						newConstructor=null;
					else newConstructor = (CtConstructor) rightTreemethod.getMetadata(SpoonGumTreeBuilder.SPOON_OBJECT);

					if (oldConstructor.getSimpleName().equals(srcCallMethodName)
							&& newConstructor.getSimpleName().equals(dstCallMethodName)) {
						boolean oldParEquals = true;
						List<CtParameter> oldMethodPars = oldConstructor.getParameters();
						for (int k = 0; k < oldMethodPars.size(); k++) {
							CtTypeReference methodParType = oldMethodPars.get(k).getType();
							if (k < srcCallArguments.size()) {
								CtTypeReference methodCallArgType = srcCallArguments.get(k);
								if (!methodParType.getQualifiedName()
										.equals(methodCallArgType.getQualifiedName())) {
									oldParEquals = false;
									break;
								}
							}
						}
						if (oldParEquals) {
							boolean newParEquals = true;
							List<CtParameter> newMethodPars = newConstructor.getParameters();
							for (int k = 0; k < newMethodPars.size(); k++) {
								CtTypeReference methodParType = newMethodPars.get(k).getType();
								if (k < dstCallArguments.size()) {
									CtTypeReference methodCallArgType = dstCallArguments.get(k);
									if (!methodParType.getQualifiedName()
											.equals(methodCallArgType.getQualifiedName())) {
										newParEquals = false;
										break;
									}
								}
							} 

							if (newParEquals) {
								wasMethodDefUpdated = true;
							}
						}
					}
				}
			}
		}
	 }
	    
	 return wasMethodDefUpdated;
  }
	
	public boolean whetherparentchanged(CtElement parent) {
		
		boolean parentchanged=false;
		for (Operation operation : diff.getAllOperations()) {
			
			if(operation.getSrcNode().equals(parent)) {
				parentchanged = true;
				break;
			}
		}
		
		return parentchanged;
	}
	
	public boolean whethervariablenewlydeclared(CtVariableAccess variableaccess) {
		
		for (Operation operation2 : operations) {
			if (operation2 instanceof InsertOperation) {
				CtElement insertedNode = operation2.getSrcNode();
				// See whether a method signature was modified
				if (insertedNode instanceof CtParameter) {
					
					if(((CtParameter)insertedNode).getSimpleName().equals(variableaccess.getVariable().getSimpleName())) {
						
						CtMethod parentParameter = ((CtParameter)insertedNode).getParent(CtMethod.class);

						CtMethod parentVarAccess = variableaccess.getParent(CtMethod.class);

						if(parentParameter!=null && parentVarAccess!=null && parentParameter.equals(parentVarAccess))
						    return true;
						
						CtConstructor parentParameterConstructor = ((CtParameter)insertedNode).getParent(CtConstructor.class);

						CtConstructor parentVarAccessConstructor = variableaccess.getParent(CtConstructor.class);

						if(parentParameterConstructor!=null && parentVarAccessConstructor!=null &&
								parentParameterConstructor.equals(parentVarAccessConstructor))
						    return true;
					}
				}
				
                if (insertedNode instanceof CtLocalVariable) {

					if(((CtLocalVariable) insertedNode).getSimpleName().equals(variableaccess.getVariable().getSimpleName())) {
						
						CtMethod parentLocalVariable = ((CtLocalVariable)insertedNode).getParent(CtMethod.class);

						CtMethod parentVarAccess = variableaccess.getParent(CtMethod.class);
						
						if(parentLocalVariable!=null && parentVarAccess!=null && parentLocalVariable.equals(parentVarAccess))
						    return true;
						
						CtConstructor parentLocalVariableConstructor = ((CtLocalVariable)insertedNode).getParent(CtConstructor.class);

						CtConstructor parentVarAccessConstructor = variableaccess.getParent(CtConstructor.class);

						if(parentLocalVariableConstructor!=null && parentVarAccessConstructor!=null &&
								parentLocalVariableConstructor.equals(parentVarAccessConstructor))
						    return true;
					}
				}
                
                if (insertedNode instanceof CtField) {

					if(((CtField) insertedNode).getSimpleName().equals(variableaccess.getVariable().getSimpleName()))
						return true;	
				}
			}	
		}
		
		return false;
	}
	
	public boolean whethervariableDeclarationUpdated (CtVariableAccess varaccessoriginal, CtVariableAccess varaccessnew) {
		
		for (Operation operation2 : operations) {
			
			if (operation2 instanceof UpdateOperation) {
								
				CtElement srcNode = operation2.getSrcNode();
				CtElement dstNode = operation2.getDstNode();
				
				CtElement originalparent=getVarDeclaration(srcNode);
				CtElement newparent=getVarDeclaration(dstNode);

				if(originalparent!=null && newparent!=null) {
					if(((CtVariable)originalparent).getSimpleName().equals(varaccessoriginal.getVariable().getSimpleName())
							&& ((CtVariable)newparent).getSimpleName().equals(varaccessnew.getVariable().getSimpleName())) {
						
						if(originalparent instanceof CtParameter) {
							if(newparent instanceof CtParameter) {
								CtMethod parentParameterOriginal = originalparent.getParent(CtMethod.class);

								CtMethod parentVarAccessOriginal = varaccessoriginal.getParent(CtMethod.class);
								
								CtMethod parentParameterNew = newparent.getParent(CtMethod.class);

								CtMethod parentVarAccessNew = varaccessnew.getParent(CtMethod.class);

								if(parentParameterOriginal!=null && parentVarAccessOriginal!=null && parentParameterOriginal.equals(parentVarAccessOriginal) &&
									parentParameterNew!=null && parentVarAccessNew!=null && parentParameterNew.equals(parentVarAccessNew))
								    return true;
								
								CtConstructor parentParameterOriginalCon = originalparent.getParent(CtConstructor.class);

								CtConstructor parentVarAccessOriginalCon = varaccessoriginal.getParent(CtConstructor.class);
								
								CtConstructor parentParameterNewCon = newparent.getParent(CtConstructor.class);

								CtConstructor parentVarAccessNewCon = varaccessnew.getParent(CtConstructor.class);

								if(parentParameterOriginalCon!=null && parentVarAccessOriginalCon!=null && parentParameterOriginalCon.equals(parentVarAccessOriginalCon) &&
									parentParameterNewCon!=null && parentVarAccessNewCon!=null && parentParameterNewCon.equals(parentVarAccessNewCon))
								    return true;
							}
						}
						
						if(originalparent instanceof CtLocalVariable) {
							if(newparent instanceof CtLocalVariable) {
								CtMethod parentLocalVariableOriginal = originalparent.getParent(CtMethod.class);

								CtMethod parentVarAccessOriginal = varaccessoriginal.getParent(CtMethod.class);
								
								CtMethod parentLocalVariableNew = newparent.getParent(CtMethod.class);

								CtMethod parentVarAccessNew = varaccessnew.getParent(CtMethod.class);

								if(parentLocalVariableOriginal!=null && parentVarAccessOriginal!=null && parentLocalVariableOriginal.equals(parentVarAccessOriginal) &&
										parentLocalVariableNew!=null && parentVarAccessNew!=null && parentLocalVariableNew.equals(parentVarAccessNew))
								    return true;
								
								CtConstructor parentLocalVariableOriginalCon = originalparent.getParent(CtConstructor.class);

								CtConstructor parentVarAccessOriginalCon = varaccessoriginal.getParent(CtConstructor.class);
								
								CtConstructor parentLocalVariableNewCon = newparent.getParent(CtConstructor.class);

								CtConstructor parentVarAccessNewCon = varaccessnew.getParent(CtConstructor.class);

								if(parentLocalVariableOriginalCon!=null && parentVarAccessOriginalCon!=null && parentLocalVariableOriginalCon.equals(parentVarAccessOriginalCon) &&
										parentLocalVariableNewCon!=null && parentVarAccessNewCon!=null && parentLocalVariableNewCon.equals(parentVarAccessNewCon))
								    return true;
							}
						}
						
						if(originalparent instanceof CtField) {
							if(newparent instanceof CtField) {
								return true;
							}
						}
						
					}	
				}
			}	
		}
		
		return false;
	}
	
	public CtElement getVarDeclaration (CtElement input) {
		
		CtElement varparent=input;
		
		while(varparent!=null) {
			if(varparent instanceof CtParameter || varparent instanceof CtLocalVariable || varparent instanceof CtField) {
				return varparent;
			}
			else varparent = varparent.getParent();
		}
		
		return null;	
	}	
	
	private boolean whethervarwrapped(CtElement srcNode, CtElement node2) {
		
		boolean wasVariableWrapped = false;

		List<CtExpression> invocationArguments = new ArrayList<>();
		if (node2 instanceof CtInvocation) {
			invocationArguments = ((CtInvocation) node2).getArguments();
		}
		if (node2 instanceof CtConstructorCall) {
			invocationArguments = ((CtConstructorCall) node2).getArguments();
		}
		
		for (CtExpression ctExpression : invocationArguments) {
			if (srcNode instanceof CtVariableAccess
					&& ctExpression instanceof CtVariableAccess) {
				CtVariableAccess srcVariableAccess = (CtVariableAccess) srcNode;
				CtVariableAccess dstVariableAccess = (CtVariableAccess) ctExpression;
				if (srcVariableAccess.getVariable().getSimpleName().equals(
						dstVariableAccess.getVariable().getSimpleName())) {
					wasVariableWrapped = true;
					break;
				}
			} else if (srcNode instanceof CtTypeAccess
						&& ctExpression instanceof CtTypeAccess) {
					CtTypeAccess srcTypeAccess = (CtTypeAccess) srcNode;
					CtTypeAccess dstTypeAccess = (CtTypeAccess) ctExpression;
					if (srcTypeAccess.getAccessedType().getSimpleName().equals(
							dstTypeAccess.getAccessedType().getSimpleName())) {
						wasVariableWrapped = true;
						break;
					}
			 }
			else if (srcNode instanceof CtInvocation
					&& ctExpression instanceof CtInvocation) {
				CtInvocation originalinvocation = (CtInvocation) srcNode;
				CtInvocation otherinvocation = (CtInvocation) ctExpression;
				if (originalinvocation.getExecutable().getSignature().equals(
						otherinvocation.getExecutable().getSignature())) {
					wasVariableWrapped = true;
					break;
				}
		   }
			else if (srcNode instanceof CtConstructorCall
					&& ctExpression instanceof CtConstructorCall) {
				CtConstructorCall originalcall = (CtConstructorCall) srcNode;
				CtConstructorCall othercall = (CtConstructorCall) ctExpression;
				
				if (originalcall.getExecutable().getSignature().equals(
						othercall.getExecutable().getSignature())) {
					wasVariableWrapped = true;
					break;
				}
		   }
		}
	
		return wasVariableWrapped;
	}
}
