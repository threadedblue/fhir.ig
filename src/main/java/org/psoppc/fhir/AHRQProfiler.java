package org.psoppc.fhir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.hl7.fhir.ElementDefinition;
import org.hl7.fhir.ElementDefinitionDiscriminator;
import org.hl7.fhir.ElementDefinitionSlicing;
import org.hl7.fhir.StructureDefinition;
import org.hl7.fhir.UnsignedInt;
import org.hl7.fhir.emf.FHIRSerDeser;
import org.hl7.fhir.emf.Finals;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AHRQProfiler implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(AHRQProfiler.class);

	public static final java.lang.String ECORE_GENMODEL_URL = "http://www.eclipse.org/emf/2002/GenModel";
	public static final java.lang.String HL7_FHIR_URL = "http://hl7.org/fhir";

    private CmdLineParser CLI;

    @Option(name = "-p", aliases = "--profile", required = false, usage = "Path to the profile")
    private String profile;

    @Option(name = "-i", aliases = "--input", required = false, usage = "Path to fhir.ecore")
    private String input;

    @Option(name = "-o", aliases = "--output", required = false, usage = "Path to out.ecore.")
    private String output;

	@Option(name = "-h", aliases = {"--help"}, help = true, usage = "Display help")
	private boolean help;

    public AHRQProfiler(String[] args) throws CmdLineException {
        try {
			CLI = new CmdLineParser(AHRQProfiler.this);
            CLI.parseArgument(args);
        } catch (CmdLineException e) {
            log.error("", e);
        }
    }

	@Override
	public void run() {
		try {
			EPackage fullSpec = loadSpec();
			StructureDefinition sd = loadProfile();

			Set<String> snapshotTypeNames = collectSnapshotTypeNames(sd);
			Set<EClassifier> retainSet = collectClassifiersToRetain(fullSpec, snapshotTypeNames);

			EPackage prunedSpec = copyAndPruneSpec(fullSpec, retainSet);

			OutputStream writer = FHIRSerDeser.save((EObject)prunedSpec, Finals.SDS_FORMAT.ECORE);
			try {
				FileWriter fileOut = new FileWriter(new File(output));
				fileOut.write(writer.toString());
				fileOut.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			log.info("Pruned Ecore written to: " + output);
		} catch (Exception e) {
			log.error("Error during pruning process", e);
		}
	}

	private Set<String> collectSnapshotTypeNames(StructureDefinition sd) {
		Set<String> names = new HashSet<>();
		for (ElementDefinition ed : sd.getSnapshot().getElement()) {
			String path = ed.getPath().getValue(); // e.g. "AdverseEvent.identifier"
			if (path.contains(".")) {
				String typeName = path.substring(0, path.indexOf('.'));
				names.add(typeName);
			} else {
				names.add(path);
			}
		}
		return names;
	}

	private Set<EClassifier> collectClassifiersToRetain(EPackage spec, Set<String> initialNames) {
		Set<EClassifier> retainSet = new HashSet<>();
		for (String name : initialNames) {
			EClassifier cls = spec.getEClassifier(name);
			if (cls != null) {
				collectDependencies(cls, retainSet);
			} else {
				log.warn("Classifier not found in spec: " + name);
			}
		}
		return retainSet;
	}

	private void collectDependencies(EClassifier cls, Set<EClassifier> visited) {
		if (!visited.add(cls)) return;
	
		if (cls instanceof EClass ec) {
			for (EStructuralFeature feature : ec.getEStructuralFeatures()) {
				EClassifier refType = feature.getEType();
				if (refType != null) {
					collectDependencies(refType, visited);
				}
			}
		}
	}

	private EPackage copyAndPruneSpec(EPackage fullSpec, Set<EClassifier> retainSet) {
		EPackage newPkg = EcoreFactory.eINSTANCE.createEPackage();
		newPkg.setName(fullSpec.getName());
		newPkg.setNsURI(fullSpec.getNsURI());
		newPkg.setNsPrefix(fullSpec.getNsPrefix());
	
		EcoreUtil.Copier copier = new EcoreUtil.Copier(true, true);
		for (EClassifier retained : retainSet) {
			EClassifier copied = (EClassifier) copier.copy(retained);
			newPkg.getEClassifiers().add(copied);
		}
		copier.copyReferences();
	
		return newPkg;
	}

	// private EPackage copyAndPruneSpec(EPackage fullSpec, Set<EClassifier> retainSet) {
	// 	EPackage copy = copySpec(fullSpec);
	// 	List<EClassifier> toRemove = new ArrayList<>();

	// 	for (EClassifier cls : copy.getEClassifiers()) {
	// 		if (retainSet.stream().noneMatch(retained -> retained.getName().equals(cls.getName()))) {
	// 			toRemove.add(cls);
	// 		}
	// 	}

	// 	copy.getEClassifiers().removeAll(toRemove);
	// 	return copy;
	// }

	
    public boolean isHelp() {
        return help;
    }

    private void printUsage() {
        System.out.println("Usage:");
        CLI.printUsage(System.out);
    }

	Boolean isInSpec(String elemClassName, EPackage spec) {
		EClassifier elemClassifier = spec.getEClassifier(elemClassName);
		log.debug("isInSpec elemClassName={}", elemClassName);
		if (elemClassifier == null) {
			return false;
		 } else {
			return (elemClassifier instanceof EClass);
		 }
	}

	public void applyBounds(ElementDefinition snapshotElem, EStructuralFeature outFeature) {
		applyLowerBounds(snapshotElem, outFeature);
		applyUpperBounds(snapshotElem, outFeature);
	}

	public void applyLowerBounds(ElementDefinition snapshotElem, EStructuralFeature outFeature) {
		UnsignedInt uint = snapshotElem.getMin();
		BigInteger bigInt = uint.getValue();
		Integer min = bigInt.intValue();

		if (min != null) {
			outFeature.setLowerBound(min);
		}
	}

	public void applyUpperBounds(ElementDefinition snapshotElem, EStructuralFeature outFeature) {
		String maxStr = snapshotElem.getMax().getValue();
		if (maxStr != null) {
			if ("*".equals(maxStr)) {
				outFeature.setUpperBound(EStructuralFeature.UNBOUNDED_MULTIPLICITY);
			} else {
				try {
					outFeature.setUpperBound(Integer.parseInt(maxStr));
				} catch (NumberFormatException e) {
					System.err.println("⚠️ Invalid max cardinality: " + maxStr);
				}
			}
		}
	}

	public void applySlice(ElementDefinition snapshotElem, EStructuralFeature outFeature) {

		ElementDefinitionSlicing slicing = snapshotElem.getSlicing();

		if (slicing == null) {
			return;
		}

		// Create an annotation for the slicing metadata
		EAnnotation slicingAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
		slicingAnnotation.setSource("http://hl7.org/fhir/slicing");

		// Add discriminator(s)
		int index = 0;
		for (ElementDefinitionDiscriminator discriminator : slicing.getDiscriminator()) {
			String type = discriminator.getType().getValue().getLiteral();
			String path = discriminator.getPath().getValue();
			slicingAnnotation.getDetails().put("discriminator:" + index, type + ":" + path);
			index++;
		}

		// Add other slicing attributes
		if (slicing.getRules() != null) {
			slicingAnnotation.getDetails().put("rules", slicing.getRules().getValue().getLiteral());
		}
		if (slicing.getOrdered() != null) {
			slicingAnnotation.getDetails().put("ordered", slicing.getOrdered().toString());
		}
		if (slicing.getDescription() != null && !slicing.getDescription().getValue().isEmpty()) {
			slicingAnnotation.getDetails().put("description", slicing.getDescription().getValue());
		}

		// Attach the annotation to the EStructuralFeature
		outFeature.getEAnnotations().add(slicingAnnotation);
	}


	StructureDefinition loadProfile() {
		InputStream reader = AHRQProfiler.class.getClassLoader()
			.getResourceAsStream(profile);
		return (StructureDefinition) FHIRSerDeser.load(reader, Finals.SDS_FORMAT.XML);
	}

	EPackage loadSpec() {
		InputStream reader = AHRQProfiler.class.getClassLoader()
			.getResourceAsStream(input);
			log.debug("reader=" + reader);
		return (EPackage) FHIRSerDeser.load(reader, Finals.SDS_FORMAT.ECORE);
	}

	EPackage copySpec(EPackage spec) {
		return (EPackage) EcoreUtil.copy(spec);
	}

	void clearClassifiers(EPackage pkg) {
		pkg.getEClassifiers().clear();
	}

	private EClassifier copyClassifier(EClassifier original) {
		EcoreUtil.Copier copier = new EcoreUtil.Copier(true, true);

		// First copy the full content tree of the classifier
		copier.copy(original); // copies the classifier only
		copier.copyAll(original.eContents()); // copies features, operations, parameters, etc.
	
		// Then resolve cross-references
		copier.copyReferences();
		log.debug(original.toString());
		log.debug(copier.get(original).toString());
		return (EClassifier) copier.get(original);
	}

	private EStructuralFeature copyFeature(EStructuralFeature original) {
		return (EStructuralFeature) EcoreUtil.copy(original);
	}

   
	// public static void applyDifferentialUpdates(EPackage outputPackage, EList<ElementDefinition> differentialElements) {
    //     for (ElementDefinition diffElem : differentialElements) {
    //         String path = diffElem.getPath().getValue(); // e.g., "AdverseEvent.actuality"
    //         String[] pathParts = path.split("\\.");
    //         if (pathParts.length < 2) continue;

    //         String className = pathParts[0];
    //         String featureName = pathParts[1];

    //         EClassifier classifier = outputPackage.getEClassifier(className);
    //         if (!(classifier instanceof EClass)) {
    //             System.out.println("⚠️ Class not found: " + className);
    //             continue;
    //         }

    //         EClass eClass = (EClass) classifier;
    //         EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
    //         if (feature == null) {
    //             System.out.println("⚠️ Feature not found: " + featureName + " in class " + className);
    //             continue;
    //         }

    //         // Example: Apply MustSupport as an annotation
    //         if (diffElem.getMustSupport().isValue()) {
    //             EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
    //             if (annotation == null) {
    //                 annotation = EcoreFactory.eINSTANCE.createEAnnotation();
    //                 annotation.setSource("fhir");
    //                 feature.getEAnnotations().add(annotation);
    //             }
    //             annotation.getDetails().put("mustSupport", "true");
    //         }
	
	public static void main(String[] args) {
        try {
            AHRQProfiler app = new AHRQProfiler(args);
            log.info("Start==>");
			if (app.isHelp()) {
                app.printUsage();
                return;
            }
            app.run();
            log.info("<==Finish");
        } catch (CmdLineException e) {
            log.error("Soaping is wrong.", e);
        }
    }
}
