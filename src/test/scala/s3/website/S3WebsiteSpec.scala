package s3.website

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.cloudfront.AmazonCloudFront
import com.amazonaws.services.cloudfront.model.{CreateInvalidationRequest, CreateInvalidationResult, TooManyInvalidationsInProgressException}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import org.apache.commons.codec.digest.DigestUtils._
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Matchers, Mockito}
import org.specs2.mutable.{BeforeAfter, Specification}
import org.specs2.specification.Scope
import s3.website.CloudFront.CloudFrontSetting
import s3.website.Diff.DELETE_NOTHING_MAGIC_WORD
import s3.website.Push.{CliArgs}
import s3.website.S3.S3Setting
import s3.website.model.Config.S3_website_yml
import s3.website.model.Ssg.automaticallySupportedSiteGenerators
import s3.website.model._

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Random

class S3WebsiteSpec extends Specification {

  "gzip: true" should {
    "update a gzipped S3 object if the contents has changed" in new BasicSetup {
      config = "gzip: true"
      setLocalFileWithContent(("styles.css", "<h1>hi again</h1>"))
      setS3Files(S3File("styles.css", "1c5117e5839ad8fc00ce3c41296255a1" /* md5 of the gzip of the file contents */))
      push
      sentPutObjectRequest.getKey must equalTo("styles.css")
    }

    "not update a gzipped S3 object if the contents has not changed" in new BasicSetup {
      config = "gzip: true"
      setLocalFileWithContent(("styles.css", "<h1>hi</h1>"))
      setS3Files(S3File("styles.css", "1c5117e5839ad8fc00ce3c41296255a1" /* md5 of the gzip of the file contents */))
      push
      noUploadsOccurred must beTrue
    }
  }

  """
    gzip:
      - .xml
  """ should {
    "update a gzipped S3 object if the contents has changed" in new BasicSetup {
      config = """
        |gzip:
        |  - .xml
      """.stripMargin
      setLocalFileWithContent(("file.xml", "<h1>hi again</h1>"))
      setS3Files(S3File("file.xml", "1c5117e5839ad8fc00ce3c41296255a1" /* md5 of the gzip of the file contents */))
      push
      sentPutObjectRequest.getKey must equalTo("file.xml")
    }
  }

  "push" should {
    "not upload a file if it has not changed" in new BasicSetup {
      setLocalFileWithContent(("index.html", "<div>hello</div>"))
      setS3Files(S3File("index.html", md5Hex("<div>hello</div>")))
      push
      noUploadsOccurred must beTrue
    }

    "update a file if it has changed" in new BasicSetup {
      setLocalFileWithContent(("index.html", "<h1>old text</h1>"))
      setS3Files(S3File("index.html", md5Hex("<h1>new text</h1>")))
      push
      sentPutObjectRequest.getKey must equalTo("index.html")
    }

    "create a file if does not exist on S3" in new BasicSetup {
      setLocalFile("index.html")
      push
      sentPutObjectRequest.getKey must equalTo("index.html")
    }

    "delete files that are on S3 but not on local file system" in new BasicSetup {
      setS3Files(S3File("old.html", md5Hex("<h1>old text</h1>")))
      push
      sentDelete must equalTo("old.html")
    }

    "try again if the upload fails" in new BasicSetup {
      setLocalFile("index.html")
      uploadFailsAndThenSucceeds(howManyFailures = 5)
      push
      verify(amazonS3Client, times(6)).putObject(Matchers.any(classOf[PutObjectRequest]))
    }

    "not try again if the upload fails on because of invalid credentials" in new BasicSetup {
      setLocalFile("index.html")
      when(amazonS3Client.putObject(Matchers.any(classOf[PutObjectRequest]))).thenThrow {
        val e = new AmazonServiceException("your credentials are incorrect")
        e.setStatusCode(403)
        e
      }
      push
      verify(amazonS3Client, times(1)).putObject(Matchers.any(classOf[PutObjectRequest]))
    }

    "try again if the request times out"  in new BasicSetup {
      var attempt = 0
      when(amazonS3Client putObject Matchers.any(classOf[PutObjectRequest])) thenAnswer new Answer[PutObjectResult] {
        def answer(invocation: InvocationOnMock) = {
          attempt += 1
          if (attempt < 2) {
            val e = new AmazonServiceException("Too long a request")
            e.setStatusCode(400)
            e.setErrorCode("RequestTimeout")
            throw e
          } else {
            new PutObjectResult
          }
        }
      }
      setLocalFile("index.html")
      val exitStatus = push
      verify(amazonS3Client, times(2)).putObject(Matchers.any(classOf[PutObjectRequest]))
    }

    "try again if the delete fails" in new BasicSetup {
      setS3Files(S3File("old.html", md5Hex("<h1>old text</h1>")))
      deleteFailsAndThenSucceeds(howManyFailures = 5)
      push
      verify(amazonS3Client, times(6)).deleteObject(Matchers.anyString(), Matchers.anyString())
    }

    "try again if the object listing fails" in new BasicSetup {
      setS3Files(S3File("old.html", md5Hex("<h1>old text</h1>")))
      objectListingFailsAndThenSucceeds(howManyFailures = 5)
      push
      verify(amazonS3Client, times(6)).listObjects(Matchers.any(classOf[ListObjectsRequest]))
    }
  }

  "push with CloudFront" should {
    "invalidate the updated CloudFront items" in new BasicSetup {
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFiles("css/test.css", "articles/index.html")
      setOutdatedS3Keys("css/test.css", "articles/index.html")
      push
      sentInvalidationRequest.getInvalidationBatch.getPaths.getItems.toSeq.sorted must equalTo(("/css/test.css" :: "/articles/index.html" :: Nil).sorted)
    }

    "not send CloudFront invalidation requests on new objects"  in new BasicSetup {
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("newfile.js")
      push
      noInvalidationsOccurred must beTrue
    }

    "not send CloudFront invalidation requests on redirect objects" in new BasicSetup {
      config = """
        |cloudfront_distribution_id: EGM1J2JJX9Z
        |redirects:
        |  /index.php: index.html
      """.stripMargin
      push
      noInvalidationsOccurred must beTrue
    }

    "retry CloudFront responds with TooManyInvalidationsInProgressException" in new BasicSetup {
      setTooManyInvalidationsInProgress(4)
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("test.css")
      setOutdatedS3Keys("test.css")
      push must equalTo(0) // The retries should finally result in a success
      sentInvalidationRequests.length must equalTo(4)
    }

    "retry if CloudFront is temporarily unreachable" in new BasicSetup {
      invalidationsFailAndThenSucceed(5)
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("test.css")
      setOutdatedS3Keys("test.css")
      push
      sentInvalidationRequests.length must equalTo(6)
    }

    "encode unsafe characters in the keys" in new BasicSetup {
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("articles/arnold's file.html")
      setOutdatedS3Keys("articles/arnold's file.html")
      push
      sentInvalidationRequest.getInvalidationBatch.getPaths.getItems.toSeq.sorted must equalTo(("/articles/arnold's%20file.html" :: Nil).sorted)
    }

    "invalidate the root object '/' if a top-level object is updated or deleted" in new BasicSetup {
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("maybe-index.html")
      setOutdatedS3Keys("maybe-index.html")
      push
      sentInvalidationRequest.getInvalidationBatch.getPaths.getItems.toSeq.sorted must equalTo(("/" :: "/maybe-index.html" :: Nil).sorted)
    }
  }

  "cloudfront_invalidate_root: true" should {
    "convert CloudFront invalidation paths with the '/index.html' suffix into '/'"  in new BasicSetup {
      config = """
        |cloudfront_distribution_id: EGM1J2JJX9Z
        |cloudfront_invalidate_root: true
      """.stripMargin
      setLocalFile("articles/index.html")
      setOutdatedS3Keys("articles/index.html")
      push
      sentInvalidationRequest.getInvalidationBatch.getPaths.getItems.toSeq.sorted must equalTo(("/articles/" :: Nil).sorted)
    }
  }

  "a site with over 1000 items" should {
    "split the CloudFront invalidation requests into batches of 1000 items" in new BasicSetup {
      val files = (1 to 1002).map { i => s"lots-of-files/file-$i"}
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFiles(files:_*)
      setOutdatedS3Keys(files:_*)
      push
      sentInvalidationRequests.length must equalTo(2)
      sentInvalidationRequests(0).getInvalidationBatch.getPaths.getItems.length must equalTo(1000)
      sentInvalidationRequests(1).getInvalidationBatch.getPaths.getItems.length must equalTo(2)
    }
  }

  "push exit status" should {
    "be 0 all uploads succeed" in new BasicSetup {
      setLocalFiles("file.txt")
      push must equalTo(0)
    }

    "be 1 if any of the uploads fails" in new BasicSetup {
      setLocalFiles("file.txt")
      when(amazonS3Client.putObject(Matchers.any(classOf[PutObjectRequest]))).thenThrow(new AmazonServiceException("AWS failed"))
      push must equalTo(1)
    }

    "be 1 if any of the redirects fails" in new BasicSetup {
      config = """
        |redirects:
        |  index.php: /index.html
      """.stripMargin
      when(amazonS3Client.putObject(Matchers.any(classOf[PutObjectRequest]))).thenThrow(new AmazonServiceException("AWS failed"))
      push must equalTo(1)
    }

    "be 0 if CloudFront invalidations and uploads succeed"in new BasicSetup {
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("test.css")
      setOutdatedS3Keys("test.css")
      push must equalTo(0)
    }

    "be 1 if CloudFront is unreachable or broken"in new BasicSetup {
      setCloudFrontAsInternallyBroken()
      config = "cloudfront_distribution_id: EGM1J2JJX9Z"
      setLocalFile("test.css")
      setOutdatedS3Keys("test.css")
      push must equalTo(1)
    }

    "be 0 if upload retry succeeds" in new BasicSetup {
      setLocalFile("index.html")
      uploadFailsAndThenSucceeds(howManyFailures = 1)
      push must equalTo(0)
    }

    "be 1 if delete retry fails" in new BasicSetup {
      setLocalFile("index.html")
      uploadFailsAndThenSucceeds(howManyFailures = 6)
      push must equalTo(1)
    }

    "be 1 if an object listing fails" in new BasicSetup {
      setS3Files(S3File("old.html", md5Hex("<h1>old text</h1>")))
      objectListingFailsAndThenSucceeds(howManyFailures = 6)
      push must equalTo(1)
    }
  }

  "s3_website.yml file" should {
    "never be uploaded" in new BasicSetup {
      setLocalFile("s3_website.yml")
      push
      noUploadsOccurred must beTrue
    }
  }

  ".env file" should { // The .env file is the https://github.com/bkeepers/dotenv file
    "never be uploaded" in new BasicSetup {
      setLocalFile(".env")
      push
      noUploadsOccurred must beTrue
    }
  }

  "exclude_from_upload: string" should {
    "result in matching files not being uploaded" in new BasicSetup {
      config = "exclude_from_upload: .DS_.*?"
      setLocalFile(".DS_Store")
      push
      noUploadsOccurred must beTrue
    }
  }

  """
     exclude_from_upload:
       - regex
       - another_exclusion
  """ should {
    "result in matching files not being uploaded" in new BasicSetup {
      config = """
        |exclude_from_upload:
        |  - .DS_.*?
        |  - logs
      """.stripMargin
      setLocalFiles(".DS_Store", "logs/test.log")
      push
      noUploadsOccurred must beTrue
    }
  }

  "ignore_on_server: value" should {
    "not delete the S3 objects that match the ignore value" in new BasicSetup {
      config = "ignore_on_server: logs"
      setS3Files(S3File("logs/log.txt", ""))
      push
      noDeletesOccurred must beTrue
    }

    "support non-US-ASCII files"  in new BasicSetup {
      setS3Files(S3File("tags/笔记/test.html", ""))
      config = "ignore_on_server: tags/笔记/test.html"
      push
      noDeletesOccurred must beTrue
    }
  }

  "ignore_on_server: _DELETE_NOTHING_ON_THE_S3_BUCKET_" should {
    "result in no files being deleted on the S3 bucket" in new BasicSetup {
      config = s"""
        |ignore_on_server: $DELETE_NOTHING_MAGIC_WORD
      """.stripMargin
      setS3Files(S3File("file.txt", ""))
      push
      noDeletesOccurred
    }
  }

  """
     ignore_on_server:
       - regex
       - another_ignore
  """ should {
    "not delete the S3 objects that match the ignore value" in new BasicSetup {
      config = """
        |ignore_on_server:
        |  - .*txt
      """.stripMargin
      setS3Files(S3File("logs/log.txt", ""))
      push
      noDeletesOccurred must beTrue
    }

    "support non-US-ASCII files" in new BasicSetup {
      setS3Files(S3File("tags/笔记/test.html", ""))
      config = """
                 |ignore_on_server:
                 |  - tags/笔记/test.html
               """.stripMargin
      push
      noDeletesOccurred must beTrue
    }
  }

  "site in config" should {
    "let the user deploy a site from a custom location" in new CustomSiteDirectory with EmptySite with MockAWS with DefaultRunMode {
      config = s"site: $siteDirectory"
      setLocalFile(".vimrc")

      new File(siteDirectory, ".vimrc").exists() must beTrue // Sanity check
      siteDirectory must not equalTo workingDirectory // Sanity check

      push
      sentPutObjectRequest.getKey must equalTo(".vimrc")
    }

    "not override the --site command-line switch" in new BasicSetup {
      config = s"site: dir-that-does-not-exist"
      setLocalFile(".vimrc") // This creates a file in the directory into which the --site CLI arg points
      push
      sentPutObjectRequest.getKey must equalTo(".vimrc")
    }

    automaticallySupportedSiteGenerators foreach { siteGenerator =>
      "override an automatically detected site" in new CustomSiteDirectory with EmptySite with MockAWS with DefaultRunMode {
        addContentToAutomaticallyDetectedSite(workingDirectory)
        config = s"site: $siteDirectory"
        setLocalFile(".vimrc") // Add content to the custom site directory
        push
        sentPutObjectRequest.getKey must equalTo(".vimrc")
      }

      def addContentToAutomaticallyDetectedSite(workingDirectory: File) {
        val automaticallyDetectedSiteDir = new File(workingDirectory, siteGenerator.outputDirectory)
        automaticallyDetectedSiteDir.mkdirs()
        write(new File(automaticallyDetectedSiteDir, ".bashrc"), "echo hello")
      }
    }
  }

  "max-age in config" can {
    "be applied to all files" in new BasicSetup {
      config = "max_age: 60"
      setLocalFile("index.html")
      push
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=60")
    }

    "be applied to files that match the glob" in new BasicSetup {
      config = """
        |max_age:
        |  "*.html": 90
      """.stripMargin
      setLocalFile("index.html")
      push
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=90")
    }

    "be applied to directories that match the glob" in new BasicSetup {
      config = """
        |max_age:
        |  "assets/**/*.js": 90
      """.stripMargin
      setLocalFile("assets/lib/jquery.js")
      push
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=90")
    }

    "not be applied if the glob doesn't match" in new BasicSetup {
      config = """
        |max_age:
        |  "*.js": 90
      """.stripMargin
      setLocalFile("index.html")
      push
      sentPutObjectRequest.getMetadata.getCacheControl must beNull
    }

    "be used to disable caching" in new BasicSetup {
      config = "max_age: 0"
      setLocalFile("index.html")
      push
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("no-cache; max-age=0")
    }

    "support non-US-ASCII directory names"  in new BasicSetup {
      config = """
        |max_age:
        |  "*": 21600
      """.stripMargin
      setLocalFile("tags/笔记/index.html")
      push must equalTo(0)
    }
  }

  "max-age in config" should {
    "respect the more specific glob" in new BasicSetup {
      config = """
        |max_age:
        |  "assets/*": 150
        |  "assets/*.gif": 86400
      """.stripMargin
      setLocalFiles("assets/jquery.js", "assets/picture.gif")
      push
      sentPutObjectRequests.find(_.getKey == "assets/jquery.js").get.getMetadata.getCacheControl must equalTo("max-age=150")
      sentPutObjectRequests.find(_.getKey == "assets/picture.gif").get.getMetadata.getCacheControl must equalTo("max-age=86400")
    }
  }

  "s3_reduced_redundancy: true in config" should {
    "result in uploads being marked with reduced redundancy" in new BasicSetup {
      config = "s3_reduced_redundancy: true"
      setLocalFile("file.exe")
      push
      sentPutObjectRequest.getStorageClass must equalTo("REDUCED_REDUNDANCY")
    }
  }

  "s3_reduced_redundancy: false in config" should {
    "result in uploads being marked with the default storage class" in new BasicSetup {
      config = "s3_reduced_redundancy: false"
      setLocalFile("file.exe")
      push
      sentPutObjectRequest.getStorageClass must beNull
    }
  }

  "redirect in config" should {
    "result in a redirect instruction that is sent to AWS" in new BasicSetup {
      config = """
        |redirects:
        |  index.php: /index.html
      """.stripMargin
      push
      sentPutObjectRequest.getRedirectLocation must equalTo("/index.html")
    }

    "add slash to the redirect target" in new BasicSetup {
      config = """
                 |redirects:
                 |  index.php: index.html
               """.stripMargin
      push
      sentPutObjectRequest.getRedirectLocation must equalTo("/index.html")
    }

    "support external redirects" in new BasicSetup {
      config = """
                 |redirects:
                 |  index.php: http://www.youtube.com/watch?v=dQw4w9WgXcQ
               """.stripMargin
      push
      sentPutObjectRequest.getRedirectLocation must equalTo("http://www.youtube.com/watch?v=dQw4w9WgXcQ")
    }

    "support external redirects that point to an HTTPS target" in new BasicSetup {
      config = """
                 |redirects:
                 |  index.php: https://www.youtube.com/watch?v=dQw4w9WgXcQ
               """.stripMargin
      push
      sentPutObjectRequest.getRedirectLocation must equalTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    }

    "result in max-age=0 Cache-Control header on the object" in new BasicSetup {
      config = """
        |redirects:
        |  index.php: /index.html
      """.stripMargin
      push
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=0, no-cache")
    }
  }

  "redirect in config and an object on the S3 bucket" should {
    "not result in the S3 object being deleted" in new BasicSetup {
      config = """
        |redirects:
        |  index.php: /index.html
      """.stripMargin
      setLocalFile("index.php")
      setS3Files(S3File("index.php", "md5"))
      push
      noDeletesOccurred must beTrue
    }
  }

  "dotfiles" should {
    "be included in the pushed files" in new BasicSetup {
      setLocalFile(".vimrc")
      push
      sentPutObjectRequest.getKey must equalTo(".vimrc")
    }
  }

  "content type inference" should {
    "add charset=utf-8 to all html documents" in new BasicSetup {
      setLocalFile("index.html")
      push
      sentPutObjectRequest.getMetadata.getContentType must equalTo("text/html; charset=utf-8")
    }

    "add charset=utf-8 to all text documents" in new BasicSetup {
      setLocalFile("index.txt")
      push
      sentPutObjectRequest.getMetadata.getContentType must equalTo("text/plain; charset=utf-8")
    }

    "add charset=utf-8 to all json documents" in new BasicSetup {
      setLocalFile("data.json")
      push
      sentPutObjectRequest.getMetadata.getContentType must equalTo("application/json; charset=utf-8")
    }

    "resolve the content type from file contents" in new BasicSetup {
      setLocalFileWithContent(("index", "<html><body><h1>hi</h1></body></html>"))
      push
      sentPutObjectRequest.getMetadata.getContentType must equalTo("text/html; charset=utf-8")
    }
  }

  "ERB in config file" should {
    "be evaluated"  in new BasicSetup {
      config = """
        |redirects:
        |<%= ('a'..'f').to_a.map do |t| '  '+t+ ': /'+t+'.html' end.join('\n')%>
      """.stripMargin
      push
      sentPutObjectRequests.length must equalTo(6)
      sentPutObjectRequests.forall(_.getRedirectLocation != null) must beTrue
    }
  }

  "dry run" should {
    "not push updates" in new SiteLocationFromCliArg with EmptySite with MockAWS with DryRunMode {
      setLocalFileWithContent(("index.html", "<div>new</div>"))
      setS3Files(S3File("index.html", md5Hex("<div>old</div>")))
      push
      noUploadsOccurred must beTrue
    }

    "not push redirects" in new SiteLocationFromCliArg with EmptySite with MockAWS with DryRunMode {
      config =
        """
          |redirects:
          |  index.php: /index.html
        """.stripMargin
      push
      noUploadsOccurred must beTrue
    }

    "not push deletes" in new SiteLocationFromCliArg with EmptySite with MockAWS with DryRunMode {
      setS3Files(S3File("index.html", md5Hex("<div>old</div>")))
      push
      noUploadsOccurred must beTrue
    }

    "not push new files" in new SiteLocationFromCliArg with EmptySite with MockAWS with DryRunMode {
      setLocalFile("index.html")
      push
      noUploadsOccurred must beTrue
    }

    "not invalidate files" in new SiteLocationFromCliArg with EmptySite with MockAWS with DryRunMode {
      config = "cloudfront_invalidation_id: AABBCC"
      setS3Files(S3File("index.html", md5Hex("<div>old</div>")))
      push
      noInvalidationsOccurred must beTrue
    }
  }

  "Jekyll site" should {
    "be detected automatically" in new JekyllSite with EmptySite with MockAWS with DefaultRunMode {
      setLocalFile("index.html")
      push
      sentPutObjectRequests.length must equalTo(1)
    }
  }

  "Nanoc site" should {
    "be detected automatically" in new NanocSite with EmptySite with MockAWS with DefaultRunMode {
      setLocalFile("index.html")
      push
      sentPutObjectRequests.length must equalTo(1)
    }
  }
  
  trait BasicSetup extends SiteLocationFromCliArg with EmptySite with MockAWS with DefaultRunMode

  trait DefaultRunMode {
    implicit def pushMode: PushMode = new PushMode {
      def dryRun = false
    }
  }

  trait DryRunMode {
    implicit def pushMode: PushMode = new PushMode {
      def dryRun = true
    }
  }

  trait MockAWS extends MockS3 with MockCloudFront with Scope

  trait MockCloudFront extends MockAWSHelper {
    val amazonCloudFrontClient = mock(classOf[AmazonCloudFront])
    implicit val cfSettings: CloudFrontSetting = CloudFrontSetting(
      cfClient = _ => amazonCloudFrontClient,
      retryTimeUnit = MICROSECONDS
    )

    def sentInvalidationRequests: Seq[CreateInvalidationRequest] = {
      val createInvalidationReq = ArgumentCaptor.forClass(classOf[CreateInvalidationRequest])
      verify(amazonCloudFrontClient, Mockito.atLeastOnce()).createInvalidation(createInvalidationReq.capture())
      createInvalidationReq.getAllValues
    }

    def sentInvalidationRequest = sentInvalidationRequests.ensuring(_.length == 1).head

    def noInvalidationsOccurred = {
      verify(amazonCloudFrontClient, Mockito.never()).createInvalidation(Matchers.any(classOf[CreateInvalidationRequest]))
      true // Mockito is based on exceptions
    }

    def invalidationsFailAndThenSucceed(implicit howManyFailures: Int, callCount: AtomicInteger = new AtomicInteger(0)) {
      doAnswer(temporaryFailure(classOf[CreateInvalidationResult]))
        .when(amazonCloudFrontClient)
        .createInvalidation(Matchers.anyObject())
    }

    def setTooManyInvalidationsInProgress(attemptWhenInvalidationSucceeds: Int) {
      var callCount = 0
      doAnswer(new Answer[CreateInvalidationResult] {
        override def answer(invocation: InvocationOnMock): CreateInvalidationResult = {
          callCount += 1
          if (callCount < attemptWhenInvalidationSucceeds)
            throw new TooManyInvalidationsInProgressException("just too many, man")
          else
            mock(classOf[CreateInvalidationResult])
        }
      }).when(amazonCloudFrontClient).createInvalidation(Matchers.anyObject())
    }

    def setCloudFrontAsInternallyBroken() {
      when(amazonCloudFrontClient.createInvalidation(Matchers.anyObject())).thenThrow(new AmazonServiceException("CloudFront is down"))
    }
  }

  trait MockS3 extends MockAWSHelper {
    val amazonS3Client = mock(classOf[AmazonS3])
    implicit val s3Settings: S3Setting = S3Setting(
      s3Client = _ => amazonS3Client,
      retryTimeUnit = MICROSECONDS
    )
    val s3ObjectListing = new ObjectListing
    when(amazonS3Client.listObjects(Matchers.any(classOf[ListObjectsRequest]))).thenReturn(s3ObjectListing)

    def setOutdatedS3Keys(s3Keys: String*) {
      s3Keys
        .map(key =>
          S3File(key, md5Hex(Random.nextLong().toString)) // Simulate the situation where the file on S3 is outdated (as compared to the local file)
        )
        .foreach (setS3Files(_))
    }

    def setS3Files(s3Files: S3File*) {
      s3Files.foreach { s3File =>
        s3ObjectListing.getObjectSummaries.add({
          val summary = new S3ObjectSummary
          summary.setETag(s3File.md5)
          summary.setKey(s3File.s3Key)
          summary
        })
      }
    }

    def removeAllFilesFromS3() {
      setS3Files(Nil: _*) // This corresponds to the situation where the S3 bucket is empty
    }

    def uploadFailsAndThenSucceeds(implicit howManyFailures: Int, callCount: AtomicInteger = new AtomicInteger(0)) {
      doAnswer(temporaryFailure(classOf[PutObjectResult]))
        .when(amazonS3Client)
        .putObject(Matchers.anyObject())
    }

    def deleteFailsAndThenSucceeds(implicit howManyFailures: Int, callCount: AtomicInteger = new AtomicInteger(0)) {
      doAnswer(temporaryFailure(classOf[DeleteObjectRequest]))
        .when(amazonS3Client)
        .deleteObject(Matchers.anyString(), Matchers.anyString())
    }

    def objectListingFailsAndThenSucceeds(implicit howManyFailures: Int, callCount: AtomicInteger = new AtomicInteger(0)) {
      doAnswer(temporaryFailure(classOf[ObjectListing]))
        .when(amazonS3Client)
        .listObjects(Matchers.any(classOf[ListObjectsRequest]))
    }

    def sentPutObjectRequests: Seq[PutObjectRequest] = {
      val req = ArgumentCaptor.forClass(classOf[PutObjectRequest])
      verify(amazonS3Client, Mockito.atLeast(1)).putObject(req.capture())
      req.getAllValues
    }

    def sentPutObjectRequest = sentPutObjectRequests.ensuring(_.length == 1).head

    def sentDeletes: Seq[S3Key] = {
      val deleteKey = ArgumentCaptor.forClass(classOf[S3Key])
      verify(amazonS3Client).deleteObject(Matchers.anyString(), deleteKey.capture())
      deleteKey.getAllValues
    }

    def sentDelete = sentDeletes.ensuring(_.length == 1).head

    def noDeletesOccurred = {
      verify(amazonS3Client, never()).deleteObject(Matchers.anyString(), Matchers.anyString())
      true // Mockito is based on exceptions
    }

    def noUploadsOccurred = {
      verify(amazonS3Client, never()).putObject(Matchers.any(classOf[PutObjectRequest]))
      true // Mockito is based on exceptions
    }

    type S3Key = String
  }

  trait MockAWSHelper {
    def temporaryFailure[T](clazz: Class[T])(implicit callCount: AtomicInteger, howManyFailures: Int) = new Answer[T] {
      def answer(invocation: InvocationOnMock) = {
        callCount.incrementAndGet()
        if (callCount.get() <= howManyFailures)
          throw new AmazonServiceException("AWS is temporarily down")
        else
          mock(clazz)
      }
    }
  }

  trait Directories extends BeforeAfter {
    def randomDir() = new File(FileUtils.getTempDirectory, "s3_website_dir" + Random.nextLong())
    implicit final val workingDirectory: File = randomDir()
    implicit def yamlConfig: S3_website_yml = S3_website_yml(new File(workingDirectory, "s3_website.yml"))
    val siteDirectory: File
    val configDirectory: File = workingDirectory // Represents the --config-dir=X option

    def before {
      workingDirectory :: siteDirectory :: configDirectory :: Nil foreach forceMkdir
    }

    def after {
      (workingDirectory :: siteDirectory :: configDirectory :: Nil) foreach { dir =>
        if (dir.exists) forceDelete(dir)
      }
    }
  }

  trait SiteLocationFromCliArg extends Directories {
    val siteDirectory = workingDirectory
    val siteDirFromCLIArg = true
  }

  trait JekyllSite extends Directories {
    val siteDirectory = new File(workingDirectory, "_site")
    val siteDirFromCLIArg = false
  }

  trait NanocSite extends Directories {
    val siteDirectory = new File(workingDirectory, "public/output")
    val siteDirFromCLIArg = false
  }

  trait CustomSiteDirectory extends Directories {
    val siteDirectory = randomDir()
    val siteDirFromCLIArg = false
  }

  trait EmptySite extends Directories {
    val siteDirFromCLIArg: Boolean
    type LocalFileWithContent = (String, String)

    def setLocalFile(fileName: String) = setLocalFileWithContent((fileName, ""))
    def setLocalFiles(fileNames: String*) = fileNames foreach setLocalFile
    def setLocalFilesWithContent(fileNamesAndContent: LocalFileWithContent*) = fileNamesAndContent foreach setLocalFileWithContent
    def setLocalFileWithContent(fileNameAndContent: LocalFileWithContent) = {
      val file = new File(siteDirectory, fileNameAndContent._1)
      forceMkdir(file.getParentFile)
      file.createNewFile()
      write(file, fileNameAndContent._2)
    }

    var config = ""
    val baseConfig =
    """
      |s3_id: foo
      |s3_secret: bar
      |s3_bucket: bucket
    """.stripMargin

    implicit def configString: ConfigString =
      ConfigString(
        s"""
          |$baseConfig
          |$config
        """.stripMargin
      )

    def pushMode: PushMode // Represents the --dry-run switch

    implicit def cliArgs: CliArgs =
      new CliArgs {
        def verbose = true

        def dryRun = pushMode.dryRun

        def site = if (siteDirFromCLIArg) siteDirectory.getAbsolutePath else null

        def configDir = configDirectory.getAbsolutePath
      }
  }

  def push(implicit
           emptyYamlConfig: S3_website_yml,
           configString: ConfigString,
           cliArgs: CliArgs,
           s3Settings: S3Setting,
           cloudFrontSettings: CloudFrontSetting,
           workingDirectory: File) = {
    write(emptyYamlConfig.file, configString.yaml) // Write the yaml config lazily, so that the tests can override the default yaml config
    Push.push
  }

  case class ConfigString(yaml: String)
}
