package unicon.matthews.dataloader.canvas;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import unicon.matthews.caliper.Event;
import unicon.matthews.dataloader.DataLoader;
import unicon.matthews.dataloader.MatthewsClient;
import unicon.matthews.dataloader.canvas.CanvasDataApiClient.Options;
import unicon.matthews.dataloader.canvas.io.converter.CanvasConversionService;
import unicon.matthews.dataloader.canvas.io.converter.SupportingEntities;
import unicon.matthews.dataloader.canvas.io.deserialize.CanvasDataDumpReader;
import unicon.matthews.dataloader.canvas.model.CanvasAssignmentDimension;
import unicon.matthews.dataloader.canvas.model.CanvasCourseSectionDimension;
import unicon.matthews.dataloader.canvas.model.CanvasDataDump;
import unicon.matthews.dataloader.canvas.model.CanvasDataPseudonymDimension;
import unicon.matthews.dataloader.canvas.model.CanvasEnrollmentDimension;
import unicon.matthews.dataloader.canvas.model.CanvasEnrollmentTermDimension;
import unicon.matthews.dataloader.canvas.model.CanvasPageRequest;
import unicon.matthews.dataloader.canvas.model.CanvasQuizDimension;
import unicon.matthews.dataloader.canvas.model.CanvasQuizSubmissionDimension;
import unicon.matthews.dataloader.canvas.model.CanvasQuizSubmissionFact;
import unicon.matthews.dataloader.canvas.model.CanvasQuizSubmissionHistoricalDimension;
import unicon.matthews.dataloader.canvas.model.CanvasUserDimension;
import unicon.matthews.oneroster.Enrollment;
import unicon.matthews.oneroster.LineItem;
import unicon.matthews.oneroster.User;

@Component
public class CanvasDataLoader implements DataLoader {
  
  private static Logger logger = LoggerFactory.getLogger(CanvasDataLoader.class);

  @Autowired
  private MatthewsClient matthewsClient;

  @Autowired
  private CanvasDataApiClient canvasDataApiClient;

  @Autowired
  CanvasConversionService canvasConversionService;
  
  // Sensor ID which indicates origin from this loader and the data origin (Dump vs potential of pulling via Redshift)
  private static final String SENSOR_ID_DUMP_READER = "canvas-matthews-data-loader/dump-reader";

  @Override
  public void run() {
    
    try {

      // Example of getting dumps in an inclusive date range, with downloads
//      List<CanvasDataDump> dumps = canvasDataApiClient.getDumps(LocalDate.parse("2017-01-15"),
//              LocalDate.parse("2017-01-19"), Options.builder().withArtifactDownloads().build());

      // Example of getting latest dump, but no download
//      CanvasDataDump dump = canvasDataApiClient.getLatestDump(Options.NONE);

      // Example of getting latest dump, with download
//      CanvasDataDump dump = canvasDataApiClient.getLatestDump(Options.builder().withArtifactDownloads().build());

      // Example of getting a single dump by date, without download.
      CanvasDataDump dump = canvasDataApiClient.getDump(LocalDate.parse("2017-01-22"),
              Options.builder().withArtifactDownloads().build());

      // Uncomment and use this one once you have the dump downloded and comment above.
//      CanvasDataDump dump = canvasDataApiClient.getDump(LocalDate.parse("2017-01-22"),Options.NONE);

      // Dump passed to the processors below needs to have been downloaded, or they will fail.
      
      Collection<CanvasEnrollmentTermDimension> enrollmentTerms
        = CanvasDataDumpReader.forType(CanvasEnrollmentTermDimension.class).read(dump);
      
      SupportingEntities supportingEntities = SupportingEntities.builder()
          .enrollmentTerms(enrollmentTerms)
          .build();

      Collection<CanvasCourseSectionDimension> courseSections
        = CanvasDataDumpReader.forType(CanvasCourseSectionDimension.class).read(dump);
      Map<String, unicon.matthews.oneroster.Class> classMap = new HashMap<>();

      List<unicon.matthews.oneroster.Class> classes = canvasConversionService.convertCanvasCourseSections(courseSections, supportingEntities);
      if (classes != null) {
        for (unicon.matthews.oneroster.Class klass : classes) {
          matthewsClient.postClass(klass);
          classMap.put(klass.getSourcedId(), klass);
        }
      }
      
      Collection<CanvasUserDimension> canvasUsers 
        = CanvasDataDumpReader.forType(CanvasUserDimension.class).read(dump);
      Map<String, User> userMap = new HashMap<>();
      
      List<User> users = canvasConversionService.convertCanvasUsers(canvasUsers, supportingEntities);
      if (users != null) {
        for (User user : users) {
          matthewsClient.postUser(user);
          userMap.put(user.getSourcedId(), user);
        }
      }
      
      supportingEntities = SupportingEntities.builder()
          .classes(classMap)
          .users(userMap)
          .enrollmentTerms(enrollmentTerms)
          .build();
      
      Collection<CanvasEnrollmentDimension> canvasEnrollments
        = CanvasDataDumpReader.forType(CanvasEnrollmentDimension.class).read(dump);
      Map<String, Enrollment> enrollmentMap = new HashMap<>();

      List<Enrollment> enrollments = canvasConversionService.convertCanvasEnrollments(canvasEnrollments, supportingEntities);
      if (enrollments != null) {
        for (Enrollment enrollment : enrollments) {
          matthewsClient.postEnrollment(enrollment);
          enrollmentMap.put(enrollment.getSourcedId(), enrollment);
        }
      }

      Map<String, LineItem> lineItemMap = new HashMap<>();
      Collection<CanvasAssignmentDimension> canvasAssignments
        = CanvasDataDumpReader.forType(CanvasAssignmentDimension.class).read(dump);

      List<LineItem> assignmentLineItems = canvasConversionService.convertCanvasAssignments(canvasAssignments, supportingEntities);
      if (assignmentLineItems != null) {
        for (LineItem lineItem : assignmentLineItems) {
          matthewsClient.postLineItem(lineItem);
          lineItemMap.put(lineItem.getSourcedId(), lineItem);
        }
      }

      Collection<CanvasQuizDimension> canvasQuizzes
        = CanvasDataDumpReader.forType(CanvasQuizDimension.class).read(dump);

      List<LineItem> quizLineItems = canvasConversionService.convertCanvasQuizes(canvasQuizzes, supportingEntities);
      if (quizLineItems != null) {
        for (LineItem lineItem : quizLineItems) {
          matthewsClient.postLineItem(lineItem);
          lineItemMap.put(lineItem.getSourcedId(), lineItem);
        }
      }

      Collection<CanvasDataPseudonymDimension> pseudonymDimensions = CanvasDataDumpReader.forType(
              CanvasDataPseudonymDimension.class).read(dump);

      Collection<CanvasPageRequest> pageRequests = CanvasDataDumpReader.forType(CanvasPageRequest.class).read(dump);

      supportingEntities = SupportingEntities.builder()
              .classes(classMap)
              .users(userMap)
              .pseudonymDimensions(pseudonymDimensions)
              .enrollments(enrollmentMap)
              .lineItems(lineItemMap)
              .pageRequests(pageRequests)
              .enrollmentTerms(enrollmentTerms)
              .build();


      // Example of filtering results to only include a specific artifact (when multiple available) and also to filter
      // those which have end dates and are after a specified date.
      Collection<CanvasQuizSubmissionFact> quizSubmissionFacts = CanvasDataDumpReader.forType(
              CanvasQuizSubmissionFact.class)
              .includeOnly(CanvasQuizSubmissionFact.Types.quiz_submission_fact)
              .withFilter(canvasQuizSubmissionFact ->
                      canvasQuizSubmissionFact.getDate().isPresent() ? canvasQuizSubmissionFact.getDate().get().isAfter(
                              LocalDate.parse("2016-10-21").atStartOfDay(ZoneOffset.UTC).toInstant()) : false
              ).read(dump);
      Collection<CanvasQuizSubmissionDimension> quizSubmissionDimensions = CanvasDataDumpReader.forType(
              CanvasQuizSubmissionDimension.class).read(dump);
      Collection<CanvasQuizSubmissionHistoricalDimension> quizSubmissionHistoricalDimensions =
              CanvasDataDumpReader.forType(CanvasQuizSubmissionHistoricalDimension.class).read(dump);

      // Quiz events are incomplete - need to add converter(s) and conversion method in CanvasConversionService

      // TODO - Need to develop more Page Request Event converters
      List<Event> events = canvasConversionService.convertPageRequests(pageRequests, supportingEntities);
      matthewsClient.postEvents(events, SENSOR_ID_DUMP_READER);

    } 
    catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } 

  }

}
