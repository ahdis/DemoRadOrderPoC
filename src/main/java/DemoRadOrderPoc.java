import java.util.Date;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ImagingStudy;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.ParameterComponent;
import org.hl7.fhir.r4.model.Task.TaskStatus;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.DateRangeParam;

public class DemoRadOrderPoc {

  public static boolean canHandleIncomingTask(Task task) {
    if (task.getFocus()==null) {
      return false;
    }
    // check that is our task:
    if (task.getRequester()!=null && task.getRequester().getIdentifier()!=null) {
      Identifier requesterIdentifier = task.getRequester().getIdentifier();
      boolean correctRequester = "urn:oid:2.51.1.3".equals(requesterIdentifier.getSystem()) && "7601003001136".equals(requesterIdentifier.getValue());
      if (!correctRequester) {
        return false;
      }
      if (task.getRestriction()!=null && task.getRestriction().getRecipient()!=null && task.getRestriction().getRecipient().size()==1) {
        Identifier recipientIdentifier = task.getRestriction().getRecipient().get(0).getIdentifier();
        if (recipientIdentifier!=null) {
          boolean correctRecipient = "urn:oid:2.51.1.3".equals(recipientIdentifier.getSystem()) && "7601001401310".equals(recipientIdentifier.getValue());
          if (correctRecipient) {
            return task.getIdentifier()!=null && task.getIdentifier().size()>0;
          };
        }
      }
    }
    return false;
  }
  
  public static String getResourceType(Reference reference) {
    return reference.getReference().substring(0,reference.getReference().indexOf("/"));
  }

  public static String getResourceId(Reference reference) {
    return reference.getReference().substring(reference.getReference().indexOf("/")+1);
  }

  public static void searchTasks() {
    FhirContext ctx = FhirContext.forR4();
    IGenericClient client = ctx.newRestfulGenericClient("https://test.ahdis.ch/matchbox/fhir");

    
    
    // http://build.fhir.org/ig/ahdis/ch-rad-poc/usecase-english.html#get-new-radiology-order-02
    
    DateRangeParam sinceLastUpdated = new DateRangeParam("2021-11-10", null);;

    org.hl7.fhir.r4.model.Bundle results = client
      .search()
      .forResource(Task.class)
      .where(Task.STATUS.exactly().code("ready"))
      .lastUpdated(sinceLastUpdated).sort().ascending(Task.MODIFIED)
      .returnBundle(org.hl7.fhir.r4.model.Bundle.class)
      .execute();
    
    System.out.println("Ready Tasks: "+results.getTotal());
    
    if (results.getTotal()>0) {
      for (BundleEntryComponent result : results.getEntry()) {
        System.out.println("No Tasks ready, nothing todo "+results.getTotal());
        Resource taskEntry = result.getResource();
        // We read the task that we have all the information (in search only summary elements are included)
        Task task = client.read().resource(Task.class).withId(taskEntry.getId()).execute();
        System.out.println("Checking task id "+task.getId());
        if (canHandleIncomingTask(task)) {
          Identifier taskId = task.getIdentifierFirstRep();
          System.out.println("Handling task "+taskId.getSystem()+" "+taskId.getValue());
          
          // Getting focus Reference
          
          Reference focus = task.getFocus();
          Resource bundle = (Resource) client.read().resource(getResourceType(focus)).withId(getResourceId(focus)).execute();
          
          Bundle resultBundle = new Bundle();
          resultBundle.setType(BundleType.TRANSACTION);
          BundleEntryRequestComponent post = new BundleEntryRequestComponent();
          post.setMethod(HTTPVerb.POST);
          post.setUrl("Bundle");
          resultBundle.addEntry().setResource(bundle).setRequest(post);
          
          
          // resolve all input references
          
          for (ParameterComponent input : task.getInput()) {
            boolean transferImagingStudy = false;
            if (input.getType()!=null && "ImagingStudy".equals(input.getType().getText())) {
              transferImagingStudy = true;
            }
            Reference referenceInput = (Reference) input.getValue();
            Resource refInput = (Resource) client.read().resource(getResourceType(referenceInput)).withId(getResourceId(referenceInput)).execute();
            BundleEntryRequestComponent postinput = new BundleEntryRequestComponent();
            postinput.setMethod(HTTPVerb.POST);
            postinput.setUrl(getResourceType(referenceInput));
            resultBundle.addEntry().setResource(refInput).setRequest(postinput);
            if (transferImagingStudy) {
              ImagingStudy imagingStudy = (ImagingStudy) refInput;
              // currently just checks first idenitfer
              System.out.println("should transfer imagingstudy with "+imagingStudy.getIdentifier().get(0).getSystem()+" "+imagingStudy.getIdentifier().get(0).getValue());
            }
          }
          
          
          // Update the status, lastModified and owner via PATCH operation or update the complete Task via PUT
          
          task.setStatus(TaskStatus.INPROGRESS);
          task.setLastModified(new Date());
          
          
          Identifier intermediaryId = new Identifier().setSystem("urn:oid:2.999.1.2.3").setValue("tbd");
          
          Reference intermediaryRef = new Reference();
          intermediaryRef.setIdentifier(intermediaryId);
          intermediaryRef.setDisplay("Intermediary Test");
          task.setOwner(intermediaryRef);
          client.update().resource(task).execute();
          
          
          // create a new order for the filler application, adapt the previous task 
          
          task.setStatus(TaskStatus.READY);
          task.setRequester(intermediaryRef);
          task.setAuthoredOn(new Date());
          task.setLastModified(new Date());
          task.setOwner(null);
          
          BundleEntryRequestComponent postTask = new BundleEntryRequestComponent();
          postTask.setMethod(HTTPVerb.POST);
          postTask.setUrl("Task");
          resultBundle.addEntry().setResource(task).setRequest(postTask);
          
          resultBundle.setTimestamp(new Date());
          
          String string = ctx.newXmlParser().setPrettyPrint(true).encodeResourceToString(resultBundle);
          
          // put it here on the same serve ... 
          Bundle bundleResponse = client.transaction().withBundle(resultBundle).execute();

          string = ctx.newXmlParser().setPrettyPrint(true).encodeResourceToString(bundleResponse);
          System.out.println(string);
          
          // for now only read one
          return ;
        }
      }
    } else {
      System.out.println("No Tasks ready, nothing todo "+results.getTotal());
    }
    
    
    
    
  }
  
  public static void main(String[] args) {
    searchTasks();

  }

}
