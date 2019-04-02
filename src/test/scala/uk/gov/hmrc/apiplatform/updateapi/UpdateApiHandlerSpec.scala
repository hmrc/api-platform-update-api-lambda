package uk.gov.hmrc.apiplatform.updateapi

import java.net.HttpURLConnection.{HTTP_OK, HTTP_UNAUTHORIZED}
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import io.swagger.models.Swagger
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions.mapAsJavaMap

class UpdateApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val requestBody = """{"host": "api-example-microservice.protected.mdtp"}"""
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val addApiHandler = new UpdateApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService)
  }

  "Update API Handler" should {
    "send API specification to AWS endpoint and return the updated API id" in new Setup {
      val id: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(id).build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      val response: APIGatewayProxyResponseEvent = addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> id)))
        .withBody(requestBody)
      )

      response.getStatusCode shouldEqual HTTP_OK
      response.getBody shouldEqual s"""{"restApiId":"$id"}"""
    }

    "correctly convert request event into PutRestApiRequest with correct configuration" in new Setup {
      val apiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(UUID.randomUUID().toString).build()
      val putRestApiRequestCaptor: ArgumentCaptor[PutRestApiRequest] = ArgumentCaptor.forClass(classOf[PutRestApiRequest])
      when(mockAPIGatewayClient.putRestApi(putRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)
      val swagger: Swagger = new Swagger().host("localhost")
      when(mockSwaggerService.createSwagger(any[APIGatewayProxyRequestEvent])).thenReturn(swagger)

      addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody)
      )

      val capturedRequest: PutRestApiRequest = putRestApiRequestCaptor.getValue
      capturedRequest.parameters should contain(Entry("endpointConfigurationTypes", "REGIONAL"))
      capturedRequest.failOnWarnings shouldBe true
      capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
      capturedRequest.mode() shouldEqual OVERWRITE
      capturedRequest.restApiId() shouldEqual apiId
    }

    "deploy API" in new Setup {
      val apiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody)
      )

      verify(mockDeploymentService, times(1)).deployApi(apiId)
    }

    "correctly handle UnauthorizedException thrown by AWS SDK when updating API" in new Setup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val response: APIGatewayProxyResponseEvent = addApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> UUID.randomUUID().toString)))
        .withBody(requestBody)
      )

      response.getStatusCode shouldEqual HTTP_UNAUTHORIZED
      response.getBody shouldEqual errorMessage
    }
  }
}
