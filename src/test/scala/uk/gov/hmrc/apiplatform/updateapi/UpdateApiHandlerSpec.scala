package uk.gov.hmrc.apiplatform.updateapi

import java.net.HttpURLConnection.{HTTP_OK, HTTP_UNAUTHORIZED}
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import io.swagger.models.Swagger
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.EndpointType.{PRIVATE, REGIONAL}
import software.amazon.awssdk.services.apigateway.model.Op.REPLACE
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConverters._

class UpdateApiHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val requestBody = """{"host": "api-example-microservice.protected.mdtp"}"""
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val mockSwaggerService: SwaggerService = mock[SwaggerService]
    val mockDeploymentService: DeploymentService = mock[DeploymentService]
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mock[LambdaLogger])
    when(mockAPIGatewayClient.getRestApi(any[GetRestApiRequest]))
      .thenReturn(GetRestApiResponse.builder().endpointConfiguration(EndpointConfiguration.builder().types(REGIONAL).build()).build())
  }

  trait StandardSetup extends Setup {
    val environment: Map[String, String] = Map("endpoint_type" -> "REGIONAL")
    val updateApiHandler = new UpdateApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService, environment)
  }

  trait SetupWithoutEndpointType extends Setup {
    val environment: Map[String, String] = Map()
    val updateApiHandler = new UpdateApiHandler(mockAPIGatewayClient, mockDeploymentService, mockSwaggerService, environment)
  }

  "Update API Handler" should {
    "send API specification to AWS endpoint and return the updated API id" in new StandardSetup {
      val id: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(id).build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      val response: APIGatewayProxyResponseEvent = updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> id)))
        .withBody(requestBody),
        mockContext
      )

      response.getStatusCode shouldEqual HTTP_OK
      response.getBody shouldEqual s"""{"restApiId":"$id"}"""
    }

    "correctly convert request event into PutRestApiRequest with correct configuration" in new StandardSetup {
      val apiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(apiId).build()
      val putRestApiRequestCaptor: ArgumentCaptor[PutRestApiRequest] = ArgumentCaptor.forClass(classOf[PutRestApiRequest])
      when(mockAPIGatewayClient.putRestApi(putRestApiRequestCaptor.capture())).thenReturn(apiGatewayResponse)
      val swagger: Swagger = new Swagger().host("localhost")
      when(mockSwaggerService.createSwagger(any[APIGatewayProxyRequestEvent])).thenReturn(swagger)

      updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody),
        mockContext
      )

      val capturedRequest: PutRestApiRequest = putRestApiRequestCaptor.getValue
      capturedRequest.failOnWarnings shouldBe true
      capturedRequest.body shouldEqual fromUtf8String(toJson(swagger))
      capturedRequest.mode() shouldEqual OVERWRITE
      capturedRequest.restApiId() shouldEqual apiId
    }

    "update the endpoint type if the new value doesn't match the current one in AWS" in new StandardSetup {
      val apiId: String = UUID.randomUUID().toString
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(PutRestApiResponse.builder().id(apiId).build())
      val updateRestApiRequestCaptor: ArgumentCaptor[UpdateRestApiRequest] = ArgumentCaptor.forClass(classOf[UpdateRestApiRequest])
      when(mockAPIGatewayClient.updateRestApi(updateRestApiRequestCaptor.capture())).thenReturn(UpdateRestApiResponse.builder().build())
      when(mockAPIGatewayClient.getRestApi(any[GetRestApiRequest]))
        .thenReturn(GetRestApiResponse.builder().endpointConfiguration(EndpointConfiguration.builder().types(PRIVATE).build()).build())

      updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody),
        mockContext
      )

      val capturedUpdateRequest: UpdateRestApiRequest = updateRestApiRequestCaptor.getValue
      val patchOperation: PatchOperation = capturedUpdateRequest.patchOperations().asScala.head
      patchOperation.op() shouldEqual REPLACE
      patchOperation.path() shouldEqual "/endpointConfiguration/types/PRIVATE"
      patchOperation.value() shouldEqual "REGIONAL"
    }

    "not update the endpoint type if the new value matches the current one in AWS" in new StandardSetup {
      val apiId: String = UUID.randomUUID().toString
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(PutRestApiResponse.builder().id(apiId).build())

      updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody),
        mockContext
      )

      verify(mockAPIGatewayClient, Mockito.never()).updateRestApi(any[UpdateRestApiRequest])
    }

    "default to PRIVATE if no endpoint type specified in the environment" in new SetupWithoutEndpointType {
      val apiId: String = UUID.randomUUID().toString
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(PutRestApiResponse.builder().id(apiId).build())
      val updateRestApiRequestCaptor: ArgumentCaptor[UpdateRestApiRequest] = ArgumentCaptor.forClass(classOf[UpdateRestApiRequest])
      when(mockAPIGatewayClient.updateRestApi(updateRestApiRequestCaptor.capture())).thenReturn(UpdateRestApiResponse.builder().build())

      updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody),
        mockContext
      )

      val capturedUpdateRequest: UpdateRestApiRequest = updateRestApiRequestCaptor.getValue
      val patchOperation: PatchOperation = capturedUpdateRequest.patchOperations().asScala.head
      patchOperation.op() shouldEqual REPLACE
      patchOperation.path() shouldEqual "/endpointConfiguration/types/REGIONAL"
      patchOperation.value() shouldEqual "PRIVATE"
    }

    "deploy API" in new StandardSetup {
      val apiId: String = UUID.randomUUID().toString
      val apiGatewayResponse: PutRestApiResponse = PutRestApiResponse.builder().id(apiId).build()
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenReturn(apiGatewayResponse)

      updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> apiId)))
        .withBody(requestBody),
        mockContext
      )

      verify(mockDeploymentService, times(1)).deployApi(apiId)
    }

    "correctly handle UnauthorizedException thrown by AWS SDK when updating API" in new StandardSetup {
      val errorMessage = "You're an idiot"
      when(mockAPIGatewayClient.putRestApi(any[PutRestApiRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val response: APIGatewayProxyResponseEvent = updateApiHandler.handleInput(new APIGatewayProxyRequestEvent()
        .withHttpMethod("PUT")
        .withPathParamters(mapAsJavaMap(Map("api_id" -> UUID.randomUUID().toString)))
        .withBody(requestBody),
        mockContext
      )

      response.getStatusCode shouldEqual HTTP_UNAUTHORIZED
      response.getBody shouldEqual errorMessage
    }
  }
}
