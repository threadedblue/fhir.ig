package org.hl7.fhir

class DartGenerator {
	
	val resourceSet = new ResourceSetImpl
	resourceSet.resourceFactoryRegistry.extensionToFactoryMap.put("ecore", new EcoreResourceFactoryImpl)
	
	val resource = resourceSet.getResource(URI.createFileURI("path/to/pruned.ecore"), true)
	resource.load(null)
	
	val ePackage = resource.contents.head as EPackage
	for (eClass : ePackage.EClassifiers.filter(EClass)) {
    val dartCode = '''
class «eClass.name» {
		«FOR attr : eClass.EAttributes»
		  «mapType(attr.EAttributeType)» «attr.name»;
		«ENDFOR»
		«FOR ref : eClass.EReferences»
		  «refType(ref)» «ref.name»;
		«ENDFOR»
		}
'''
    saveToFile("out/${eClass.name}.dart", dartCode)
}
	
