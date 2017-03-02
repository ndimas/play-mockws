package mockws

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.Mockito._
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FunSuite, Matchers}
import play.api.http.HttpEntity
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.libs.ws.{WSAuthScheme, WSClient, WSSignatureCalculator}
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results._
import play.api.mvc.{Action, ResponseHeader, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

/**
 * Tests that [[MockWS]] simulates a WS client
 */
class MockWSTest extends FunSuite with Matchers with PropertyChecks {

  test("mock WS simulates all HTTP methods") {
    val ws = MockWS {
      case (GET, "/get")       => Action { Ok("get ok") }
      case (POST, "/post")     => Action { Ok("post ok") }
      case (PUT, "/put")       => Action { Ok("put ok") }
      case (DELETE, "/delete") => Action { Ok("delete ok") }
      case ("PATCH", "/patch") => Action { Ok("patch ok") }
    }

    await(ws.url("/get").get()).body       shouldEqual "get ok"
    await(ws.url("/post").post("")).body   shouldEqual "post ok"
    await(ws.url("/put").put("")).body     shouldEqual "put ok"
    await(ws.url("/delete").delete()).body shouldEqual "delete ok"
    await(ws.url("/patch").patch("")).body shouldEqual "patch ok"
    ws.close()
  }


  test("mock WS simulates the HTTP status code") {
    val ws = MockWS {
      case (GET, "/get200") => Action { Ok("") }
      case (GET, "/get201") => Action { Created("") }
      case (GET, "/get404") => Action { NotFound("") }
    }

    await(ws.url("/get200").get()).status shouldEqual OK
    await(ws.url("/get201").get()).status shouldEqual CREATED
    await(ws.url("/get404").get()).status shouldEqual NOT_FOUND
    ws.close()
  }


  test("mock WS simulates a POST with a JSON payload") {
    val ws = MockWS {
      case (POST, "/") => Action { request =>
        Ok((request.body.asJson.get \ "result").as[String])
      }
    }

    val json = Json.parse("""{"result": "OK"}""")

    val response = await(ws.url("/").post(json))
    response.status shouldEqual OK
    response.body shouldEqual "OK"
    ws.close()
  }


  test("mock WS simulates a POST with a JSON payload with a custom content type") {
    val ws = MockWS {
      case (POST, "/") => Action(parse.tolerantJson) { request =>
        Ok((request.body \ "result").as[String])
      }
    }

    val json = Json.parse("""{"result": "OK"}""")

    val response = await(ws.url("/").withHeaders(CONTENT_TYPE -> "application/my-json").post(json))
    response.status shouldEqual OK
    response.body shouldEqual "OK"
    ws.close()
  }


  test("mock WS sets the response content type") {
    val ws = MockWS {
      case (GET, "/text") => Action { Ok("text") }
      case (GET, "/json") => Action { Ok(Json.parse("""{ "type": "json" }""")) }
    }

    val text = await(ws.url("/text").get())
    val json = await(ws.url("/json").get())

    text.header(CONTENT_TYPE) shouldEqual Some("text/plain; charset=utf-8")
    json.header(CONTENT_TYPE) shouldEqual Some("application/json")
    ws.close()
  }


  test("mock WS simulates a streaming") {

    def testedController(ws: WSClient) = Action.async {
      ws.url("/").stream().map { resp =>
        Result(
          header = ResponseHeader(resp.headers.status, resp.headers.headers.mapValues(_.head)),
          body = HttpEntity.Streamed(resp.body, None, None))
      }
    }

    val ws = MockWS {
      case (GET, "/") => Action {
        val body: Source[ByteString, _] = Source(Seq("first", "second", "third").map(ByteString.apply))
        Result(
          header = ResponseHeader(201, Map("x-header" -> "x-value")),
          body = HttpEntity.Streamed(body, None, None))
      }
    }
    import ws.materializer

    val response = testedController(ws).apply(FakeRequest())
    status(response) shouldEqual CREATED
    contentAsString(response) shouldEqual "firstsecondthird"
    header("x-header", response) shouldEqual Some("x-value")
    ws.close()
  }

  test("mock WS can produce JSON") {
    val ws = MockWS {
      case (GET, "/json") => Action {
        Ok(Json.obj("field" -> "value"))
      }
    }

    val wsResponse = await( ws.url("/json").get() )
    wsResponse.body shouldEqual """{"field":"value"}"""
    (wsResponse.json \ "field").asOpt[String] shouldEqual Some("value")
    ws.close()
  }


  test("mock WS can produce XML") {
    val ws = MockWS {
      case (GET, "/xml") => Action {
        Ok(<foo><bar>value</bar></foo>)
      }
    }

    val wsResponse = await( ws.url("/xml").get() )
    wsResponse.body shouldEqual "<foo><bar>value</bar></foo>"
    (wsResponse.xml \ "bar").text shouldEqual "value"
    ws.close()
  }


  test("a call to an unknown route causes an exception") {
    val ws = MockWS {
      case (GET, "/url") => Action { Ok("") }
    }

    the [Exception] thrownBy {
      ws.url("/url2").get()
    } should have message "no route defined for GET /url2"

    the [Exception] thrownBy {
      ws.url("/url").delete()
    } should have message "no route defined for DELETE /url"
    ws.close()
  }

  test("mock WS supports custom response content types") {
    val ws = MockWS {
      case (_, _) => Action {
        Ok("hello").as("hello/world")
      }
    }

    val wsResponse = await( ws.url("/").get() )
    wsResponse.status shouldEqual OK
    wsResponse.header(CONTENT_TYPE) shouldEqual Some("hello/world")
    wsResponse.body shouldEqual "hello"
    ws.close()
  }

  test("mock WS supports custom request content types") {
    val ws = MockWS {
      case (_, _) => Action { request =>
        request.contentType match {
          case Some(ct) => Ok(ct)
          case None => BadRequest("no content type")
        }
      }
    }

    val wsResponse = await( ws.url("/").withHeaders(CONTENT_TYPE -> "hello/world").get)
    wsResponse.status shouldEqual OK
    wsResponse.body shouldEqual "hello/world"
    ws.close()
  }

  test("mock WS supports query parameter") {
    forAll { (q: String, v: String) =>
      whenever(q.nonEmpty) {

        val ws = MockWS {
          case (GET, "/uri") => Action { request =>
            request.getQueryString(q).fold[Result](NotFound) {
              id => Ok(id)
            }
          }
        }

        val wsResponse =  await( ws.url("/uri").withQueryString(q -> v).get)
        wsResponse.status shouldEqual OK
        wsResponse.body shouldEqual v
        ws.close()
      }
    }
  }

  test("mock WS supports varargs passed as immutable Seqs") {
    forAll { (q: String, v: String) =>
      whenever(q.nonEmpty) {

        val ws = MockWS {
          case (GET, "/uri") => Action { request =>
            request.getQueryString(q).fold[Result](NotFound) {
              id => Ok(id)
            }
          }
        }

        await( ws.url("/uri").withHeaders(Seq(q -> v): _*).get )
        await( ws.url("/uri").withQueryString(Seq(q -> v): _*).get )
        ws.close()
      }
    }
  }

  test("mock WS supports method in execute") {
    val ws = MockWS {
      case (GET, "/get")       => Action { Ok("get ok") }
      case (POST, "/post")     => Action { Ok("post ok") }
      case (PUT, "/put")       => Action { Ok("put ok") }
      case (DELETE, "/delete") => Action { Ok("delete ok") }
    }
    
    await(ws.url("/get").withMethod("GET").execute()).body       shouldEqual "get ok"
    await(ws.url("/post").withMethod("POST").execute()).body     shouldEqual "post ok"
    await(ws.url("/put").withMethod("PUT").execute()).body       shouldEqual "put ok"
    await(ws.url("/delete").withMethod("DELETE").execute()).body shouldEqual "delete ok"
    ws.close()
  }
  
  test("mock WS supports method in stream") {
    def testedController(ws: WSClient) = Action.async {
      ws.url("/").withMethod("POST").stream().map { resp =>
        Result(
          header = ResponseHeader(resp.headers.status, resp.headers.headers.mapValues(_.head)),
          body = HttpEntity.Streamed(resp.body, None, None)
        )
      }
    }

    val ws = MockWS {
      case (POST, "/") => Action {
        val body: Source[ByteString, _] = Source(Seq("first", "second", "third").map(ByteString.apply))
        Result(
          header = ResponseHeader(201, Map("x-header" -> "x-value")),
          body = HttpEntity.Streamed(body, None, None)
        )
      }
    }
    import ws.materializer

    val response = testedController(ws).apply(FakeRequest())
    status(response) shouldEqual CREATED
    contentAsString(response) shouldEqual "firstsecondthird"
    header("x-header", response) shouldEqual Some("x-value")
    ws.close()
  }

  test("should not raise NullPointerExceptions on method chaining") {
    val ws = MockWS {
      case (GET, "/get") => Action { Ok("get ok") }
    }

    await(ws
      .url("/get")
      .sign(mock(classOf[WSSignatureCalculator]))
      .withVirtualHost("bla")
      .withFollowRedirects(follow = true)
      .withAuth("user", "password", WSAuthScheme.BASIC)
      .withRequestTimeout(10.millis)
      .get()).body shouldEqual "get ok"
    ws.close()
  }

  test("should pass through all elements of a Source") {
    val content = Source(Seq("hello, ", "world").map(ByteString(_)))

    val ws = MockWS {
      case (GET, "/get") ⇒ Action {
        Result(
          header = ResponseHeader(200),
          body = HttpEntity.Streamed(content, None, None)
        )
      }
    }

    await(ws
      .url("/get")
      .get()).body shouldEqual "hello, world"
    ws.close()
  }
}
