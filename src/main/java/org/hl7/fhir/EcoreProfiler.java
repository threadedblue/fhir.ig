package org.hl7.fhir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.hl7.fhir.emf.FHIRSerDeser;
import org.hl7.fhir.emf.Finals;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EcoreProfiler implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(EcoreProfiler.class);

	public static final java.lang.String ECORE_GENMODEL_URL = "http://www.eclipse.org/emf/2002/GenModel";
	public static final java.lang.String HL7_FHIR_URL = "http://hl7.org/fhir";

    private CmdLineParser CLI;

    @Option(name = "-p", aliases = "--profile", required = false, usage = "Path to the profile")
    private java.lang.String profile;

    @Option(name = "-i", aliases = "--input", required = false, usage = "Path to fhir.ecore")
    private java.lang.String input;

    @Option(name = "-o", aliases = "--output", required = false, usage = "Path to out.ecore.")
    private java.lang.String output;

	@Option(name = "-h", aliases = {"--help"}, help = true, usage = "Display help")
	private boolean help;

    public EcoreProfiler(String[] args) throws CmdLineException {
        try {
			CLI = new CmdLineParser(EcoreProfiler.this);
            CLI.parseArgument(args);
        } catch (CmdLineException e) {
            log.error("", e);
        }
    }


	public void run() {
		StructureDefinition profile = loadProfile();
		EPackage spec = loadSpec();
		EPackage out = copySpec(spec);
		log.info("out.size=0 {}", out.getEClassifiers().size());
		clearClassifiers(out);
		log.info("out.size=1 {}", out.getEClassifiers().size());

		populateEcoreOut(profile, spec, out);
		OutputStream writer = FHIRSerDeser.save(out, Finals.SDS_FORMAT.ECORE);
		try {
			FileWriter fileOut = new FileWriter(new File(output));
			fileOut.write(writer.toString());
			fileOut.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

    public boolean isHelp() {
        return help;
    }

    private void printUsage() {
        System.out.println("Usage:");
        CLI.printUsage(System.out);
    }

    public void populateEcoreOut(StructureDefinition profile, EPackage spec, EPackage out) {

		StructureDefinitionSnapshot snap = profile.getSnapshot();
		for (ElementDefinition snapElem : snap.getElement()) {
			java.lang.String snapElemPath = snapElem.getPath().getValue(); // e.g., "AdverseEvent.actuality"

			java.lang.String[] parts = snapElemPath.split("\\.");
			if (parts.length == 1) continue; // skip root element
			java.lang.String parentPath = java.lang.String.join(".", Arrays.copyOf(parts, parts.length - 1));
			java.lang.String currentName = parts[parts.length - 1];

			java.lang.String snapElemClassName = parts[0];
			java.lang.String snapElemFeatureName = parts[1];

            // Find the source EClass in the spec package
            EClassifier specElemClassifier = spec.getEClassifier(snapElemClassName);
            if (!(isInSpec(snapElemClassName, spec))) {
                log.error("⚠️ Could not find EClass " + snapElemClassName + " in spec.");
                continue;
            }

            // Add to the output package
			EClassifier copyClassifier = null;
			if (!out.getEClassifiers().contains(specElemClassifier)) {
					log.info("specElemClassifier.eContents().size()0 {}", specElemClassifier.eContents().size());
					copyClassifier = copyClassifier(specElemClassifier);
					log.info("copyClassifier.eContents().size()1 {}", copyClassifier.eContents().size());
					log.info("copyClassifier.size=0 {}", out.getEClassifiers().size());
					out.getEClassifiers().add(copyClassifier);
					log.info("copyClassifier.size=1 {}", out.getEClassifiers().size());
				

				//    Find the feature in the sSeems eevpec EClass
				log.debug("snapElemPath={}", snapElemPath);
					EClass copyClass = (EClass) copyClassifier;

					EStructuralFeature outElemFeature = copyClass.getEStructuralFeature(snapElemFeatureName);
					if (outElemFeature == null) {
						log.error("⚠️ Could not find feature {} in {}", snapElemFeatureName , copyClassifier.getName());
						log.info("copyClassifier.size={}", copyClassifier.eContents().size());
						for(EObject eO : copyClassifier.eContents()) {
							log.debug("eO.eClass().getName()={}", copyClass.getName());
						}
						// for( EObject eO : copyClassifier.eContents()) {
						// 	log.info("from eO={}", eO.eClass().getName());
						// }
						continue;
					}

				// Optional: apply snapshot constraints
				applySnapshotElementToFeature(snapElem, outElemFeature);
				applySlice(snapElem, outElemFeature);
			}
		}

		StructureDefinitionDifferential diff = profile.getDifferential();
		for (ElementDefinition diffElem : diff.getElement()) {
			java.lang.String diffPath = diffElem.getPath().getValue();
			java.lang.String[] parts = diffPath.split("\\.");
			if (parts.length < 2) continue;

			java.lang.String className = parts[0];
			java.lang.String featureName = parts[parts.length - 1].toLowerCase();
	
			EClass eClass = pathToClass.get(className);
			if (eClass == null) continue;
	
			EStructuralFeature feature = eClass.getEStructuralFeatures().stream()
				.filter(f -> f.getName().equals(featureName))
				.findFirst().orElse(null);
	
			if (feature == null) continue;
	
			// Update cardinality
			if (diffElem.getMin() != null && diffElem.getMin().getValue() != null) {
				feature.setLowerBound(diffElem.getMin().getValue().intValue());
			}
			if (diffElem.getMax() != null && diffElem.getMax().getValue() != null) {
				String maxVal = diffElem.getMax().getValue();
				feature.setUpperBound("*".equals(maxVal) ? -1 : Integer.parseInt(maxVal));
			}
	
			// Add EAnnotation for other constraints, bindings, etc.
			if (diffElem.hasShort()) {
				EAnnotation annotation = feature.getEAnnotations().stream()
					.filter(a -> "fhir.short".equals(a.getSource()))
					.findFirst().orElseGet(() -> {
						EAnnotation a = EcoreFactory.eINSTANCE.createEAnnotation();
						a.setSource("fhir.short");
						feature.getEAnnotations().add(a);
						return a;
					});
				annotation.getDetails().put("value", diff.getShort());
			}
		}
	
    }

	public void applySnapshotElementToFeature(
		ElementDefinition snapshotElem,
		EStructuralFeature outFeature) {
		
		// --- Apply cardinality, slicing and anything else ---
		applyBounds(snapshotElem, outFeature);
		applySlice(snapshotElem, outFeature);

		// --- Add FHIR annotations ---
		EAnnotation fhirAnnotation = outFeature.getEAnnotation(HL7_FHIR_URL);
		if (fhirAnnotation == null) {
			fhirAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
			fhirAnnotation.setSource(HL7_FHIR_URL);
			outFeature.getEAnnotations().add(fhirAnnotation);
		}

		// mustSupport
		org.hl7.fhir.Boolean mustSupport = snapshotElem.getMustSupport();
		if (mustSupport != null) {
			if (mustSupport.isValue()) {
				fhirAnnotation.getDetails().put("mustSupport", "true");
			}
		}

		// binding
		ElementDefinitionBinding binding = snapshotElem.getBinding();
		if (binding != null) {
			java.lang.String valueSet = binding.getValueSet().getValue();
			if (valueSet != null) {
				fhirAnnotation.getDetails().put("binding.valueSet", valueSet);
			}

			Object strength = binding.getStrength(); // might be enum or string
			if (strength != null) {
				fhirAnnotation.getDetails().put("binding.strength", strength.toString());
			}
		}

		// --- Add documentation ---
		String doc = snapshotElem.getShort().getValue();
		if (doc == null) {
			doc = snapshotElem.getDefinition().getValue();
		}

		if (doc != null) {
			EAnnotation genModelAnnotation = outFeature.getEAnnotation(ECORE_GENMODEL_URL);
			if (genModelAnnotation == null) {
				genModelAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
				genModelAnnotation.setSource(ECORE_GENMODEL_URL);
				outFeature.getEAnnotations().add(genModelAnnotation);
			}

			genModelAnnotation.getDetails().put("documentation", doc);
		}
	}

	public void applyDifferential(StructureDefinition sd, EPackage ePackage) {
		EList<ElementDefinition> differentials = sd.getDifferential().getElement();
		if (differentials == null || differentials.isEmpty()) return;

		Map<String, EClass> pathToClass = new HashMap<>();
		for (EClassifier classifier : ePackage.getEClassifiers()) {
			if (classifier instanceof EClass cls) {
				pathToClass.put(cls.getName(), cls);
			}
		}

		for (ElementDefinition diff : differentials) {
			String path = diff.getPath().getValue();
			String[] parts = path.split("\\.");
			if (parts.length < 2) continue;

			String className = parts[0];
			String featureName = parts[parts.length - 1].toLowerCase();

			EClass eClass = pathToClass.get(className);
			if (eClass == null) continue;

			EStructuralFeature feature = eClass.getEStructuralFeatures().stream()
				.filter(f -> f.getName().equals(featureName))
				.findFirst().orElse(null);

			if (feature == null) continue;

			// Update cardinality
			if (diff.getMin() != null && diff.getMin().getValue() != null) {
				feature.setLowerBound(diff.getMin().getValue().intValue());
			}
			if (diff.getMax() != null && diff.getMax().getValue() != null) {
				String maxVal = diff.getMax().getValue();
				feature.setUpperBound("*".equals(maxVal) ? -1 : Integer.parseInt(maxVal));
			}

			// Add EAnnotation for other constraints, bindings, etc.
			if (diff.hasShort()) {
				EAnnotation annotation = feature.getEAnnotations().stream()
					.filter(a -> "fhir.short".equals(a.getSource()))
					.findFirst().orElseGet(() -> {
						EAnnotation a = EcoreFactory.eINSTANCE.createEAnnotation();
						a.setSource("fhir.short");
						feature.getEAnnotations().add(a);
						return a;
					});
				annotation.getDetails().put("value", diff.getShort());
			}
    }
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
		InputStream reader = EcoreProfiler.class.getClassLoader()
			.getResourceAsStream(profile);
		return (StructureDefinition) FHIRSerDeser.load(reader, Finals.SDS_FORMAT.XML);
	}

	EPackage loadSpec() {
		InputStream reader = EcoreProfiler.class.getClassLoader()
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

	// 	// Search for any fixed[x] value using EMF reflection
	// 	EClass elemClass = diffElem.eClass();
	// 	for (EStructuralFeature f : elemClass.getEStructuralFeatures()) {
	// 		if (f.getName().startsWith("fixed")) {
	// 			Object fixedValue = diffElem.eGet(f);
	// 			if (fixedValue != null) {
	// 				String fixedStr = fixedValue.toString(); // or extract details depending on type

	// 				// Add as EAnnotation to your output feature
	// 				EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
	// 				if (annotation == null) {
	// 					annotation = EcoreFactory.eINSTANCE.createEAnnotation();
	// 					annotation.setSource("fhir");
	// 					feature.getEAnnotations().add(annotation);
	// 				}

	// 				annotation.getDetails().put("fixed", fixedStr);
	// 				break; // Assume only one fixed[x] is set
	// 			}
	// 		}        
	// 	}
	// }
	// 	// Check if this ElementDefinition has a binding
	// 	ElementDefinitionBinding binding = diffElem.getBinding();
	// 	if (binding != null) {
	// 		String valueSetUrl = binding.getValueSet().getValue(); // likely a URI or Canonical type
	// 		BindingStrength strength = binding.getStrength(); // probably an enum

	// 		if (valueSetUrl != null || strength != null) {
	// 			EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
	// 			if (annotation == null) {
	// 				annotation = EcoreFactory.eINSTANCE.createEAnnotation();
	// 				annotation.setSource("fhir");
	// 				feature.getEAnnotations().add(annotation);
	// 			}

	// 			if (valueSetUrl != null) {
	// 				annotation.getDetails().put("binding.valueSet", valueSetUrl);
	// 			}

	// 			if (strength != null) {
	// 				annotation.getDetails().put("binding.strength", strength.getName()); // or getLiteral()
	// 			}
	// 		}
	// 	}
	// }

////////////////////////////	
// 		// Check for a 'binding' feature in the differential ElementDefinition
// 		EStructuralFeature bindingFeature = diffElem.eClass().getEStructuralFeature("binding");

// 		if (bindingFeature != null) {
// 			Object bindingObj = diffElem.eGet(bindingFeature);
// 			if (bindingObj instanceof EObject) {
// 				EObject binding = (EObject) bindingObj;

// 				// Get valueSet and strength from the binding EObject
// 				EStructuralFeature valueSetFeature = binding.eClass().getEStructuralFeature("valueSet");
// 				EStructuralFeature strengthFeature = binding.eClass().getEStructuralFeature("strength");

// 				String valueSetUrl = null;
// 				String strengthCode = null;

// 				if (valueSetFeature != null) {
// 					Object value = binding.eGet(valueSetFeature);
// 					if (value != null) {
// 						valueSetUrl = value.toString();
// 					}
// 				}

// 				if (strengthFeature != null) {
// 					Object value = binding.eGet(strengthFeature);
// 					if (value != null) {
// 						strengthCode = value.toString();
// 					}
// 				}

// 				// Add annotation to the feature if either value is found
// 				if (valueSetUrl != null || strengthCode != null) {
// 					EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
// 					if (annotation == null) {
// 						annotation = EcoreFactory.eINSTANCE.createEAnnotation();
// 						annotation.setSource("fhir");
// 						feature.getEAnnotations().add(annotation);
// 					}

// 					if (valueSetUrl != null) {
// 						annotation.getDetails().put("binding.valueSet", valueSetUrl);
// 					}
// 					if (strengthCode != null) {
// 						annotation.getDetails().put("binding.strength", strengthCode);
// 					}
// 				}
// 			}
// }
//	 }

	
// private void applySnapshotAndDifferential(
//     StructureDefinitionSnapshot snap,
//     StructureDefinitionDifferential diff,
//     EPackage spec,
//     EPackage out) {
//     // your earlier logic to populate `out` from `snap` and `diff`
// }

// private void writeOut(EPackage out) {
//     FHIRSerDeser.save(out, Finals.SDS_FORMAT.ECORE);
// }
	
	public static void main(String[] args) {
        try {
            EcoreProfiler app = new EcoreProfiler(args);
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

	// public static OutputStream profileResource(EObject eObject) {
	// 	URI ecoreURI =  URI.createFileURI("data/fhir.ecore");
	// 	resource = resourceSet.getResource(ecoreURI, true);
	// 	ByteArrayOutputStream writer = null;
	// 	try {
	// 		writer = new ByteArrayOutputStream();
	// 		resource.save(System.out, Collections.EMPTY_MAP);
	// 		writer.close();
	// 	} catch (JsonProcessingException e) {
	// 		log.error("", e);
	// 	} catch (IOException e) {
	// 		log.error("", e);
	// 	}
	// 	return writer;

	// }

	// void loadParse() {
		
	// 	ResourceSet resourceSet = new ResourceSetImpl();
	// 	Resource fhirResource = resourceSet.getResource(URI.createFileURI("fhir.ecore"), true);
	// 	Resource profileResource = resourceSet.getResource(URI.createFileURI("custom_profile.ecore"), true);
		
	// 	EObject fhirRoot = fhirResource.getContents().get(0);
	// 	EObject profileRoot = profileResource.getContents().get(0);
	// }

	// void iterateElements() {
	// 	for (EClassifier classifier : fhirEPackage.getEClassifiers()) {
	// 		if (classifier instanceof EClass) {
	// 			EClass eClass = (EClass) classifier;
	// 			if (profileDefinesRestrictions(eClass)) {
	// 				applyProfileConstraints(eClass, profileEPackage);
	// 			}
	// 		}
	// 	}		
	// }

	// void merge() {
	// 	Resource newEcoreResource = resourceSet.createResource(URI.createFileURI("profiled_fhir.ecore"));
	// 	newEcoreResource.getContents().add(fhirRoot);
	// 	newEcoreResource.save(Collections.EMPTY_MAP);
	// }
}
