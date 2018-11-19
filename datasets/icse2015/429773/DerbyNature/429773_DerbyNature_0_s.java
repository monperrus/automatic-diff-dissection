 /*
 
 	Derby - Class org.apache.derby.ui.nature.DerbyNature
 	
	Copyright 2002, 2004 The Apache Software Foundation or its licensors, as applicable.
	
	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at
 	
 	   http://www.apache.org/licenses/LICENSE-2.0
 	
 	Unless required by applicable law or agreed to in writing, software
 	distributed under the License is distributed on an "AS IS" BASIS,
 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 	See the License for the specific language governing permissions and
 	limitations under the License.
 
 */
 
 package org.apache.derby.ui.nature;
 
 import org.eclipse.core.runtime.CoreException;
 import org.eclipse.core.resources.IProject;
 import org.eclipse.core.resources.IProjectNature;
 
 public class DerbyNature implements IProjectNature {
 	private IProject project = null;
 	
 	public DerbyNature() {
 	}
 
 	public void configure() throws CoreException {
 	}
 
 	public void deconfigure() throws CoreException {
 	}
 
 	public IProject getProject()  {
 		return project;
 	}
 
 	public void setProject(IProject project)  {
 		this.project = project;
 	}
 }