import org.eclipse.emf.codegen.ecore.generator.Generator;
import org.eclipse.emf.codegen.ecore.genmodel.GenModel;
import org.eclipse.emf.codegen.ecore.genmodel.GenModelPackage;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

public class EMFCodeGenStandalone {
    public static void main(String[] args) throws Exception {
        // Initialize EMF
        ResourceSet resourceSet = new ResourceSetImpl();
        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("ecore", new EcoreResourceFactoryImpl());
        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("genmodel", new XMIResourceFactoryImpl());

        // Load Ecore model
        Resource ecoreResource = resourceSet.getResource(URI.createFileURI("path/to/your.ecore"), true);
        EPackage ePackage = (EPackage) ecoreResource.getContents().get(0);
        EPackage.Registry.INSTANCE.put(ePackage.getNsURI(), ePackage);

        // Load GenModel
        Resource genModelResource = resourceSet.getResource(URI.createFileURI("path/to/your.genmodel"), true);
        GenModel genModel = (GenModel) genModelResource.getContents().get(0);

        // Generate Code
        generateEMFCode(genModel);
    }

    void generate() {
        Resource newEcoreResource = resourceSet.createResource(URI.createFileURI("profiled_fhir.ecore"));
        newEcoreResource.getContents().add(fhirRoot);
        newEcoreResource.save(Collections.EMPTY_MAP);
    }

    // Does not work.
    private static void generateEMFCode(GenModel genModel) {
        genModel.reconcile();
        genModel.setCanGenerate(true);
        Generator generator = new Generator();
        generator.setInput(genModel);
        generator.generate(genModel, GenModelPackage.Literals.GEN_MODEL__MODEL_DIRECTORY);
    }
}
