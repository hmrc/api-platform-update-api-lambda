package uk.gov.hmrc.apiplatform.updateapi

import java.net.HttpURLConnection.HTTP_OK

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import software.amazon.awssdk.core.SdkBytes.fromUtf8String
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.PutMode.OVERWRITE
import software.amazon.awssdk.services.apigateway.model.{PutRestApiResponse, _}
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.ErrorRecovery.recovery
import uk.gov.hmrc.api_platform_manage_api.{DeploymentService, SwaggerService}
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.ProxiedRequestHandler

import scala.collection.JavaConversions.mapAsJavaMap
import scala.language.postfixOps
import scala.util.Try

class UpdateApiHandler(apiGatewayClient: ApiGatewayClient,
                       deploymentService: DeploymentService,
                       swaggerService: SwaggerService,
                       environment: Map[String, String])
  extends ProxiedRequestHandler {

  def this() {
    this(awsApiGatewayClient, new DeploymentService(awsApiGatewayClient), new SwaggerService, sys.env)
  }

  override def handleInput(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent = {
    Try(putApi(input)) recover recovery(context.getLogger) get
  }

  private def putApi(requestEvent: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val putApiRequest: PutRestApiRequest = PutRestApiRequest
      .builder()
      .body(fromUtf8String(toJson(swaggerService.createSwagger(requestEvent))))
      .parameters(mapAsJavaMap(Map("endpointConfigurationTypes" -> environment.getOrElse("endpoint_type", "PRIVATE"))))
      .failOnWarnings(true)
      .mode(OVERWRITE)
      .restApiId(requestEvent.getPathParameters.get("api_id"))
      .build()

    val putRestApiResponse: PutRestApiResponse = apiGatewayClient.putRestApi(putApiRequest)
    deploymentService.deployApi(putRestApiResponse.id())

    new APIGatewayProxyResponseEvent()
      .withStatusCode(HTTP_OK)
      .withBody(toJson(UpdateApiResponse(putRestApiResponse.id())))
  }

}

case class UpdateApiResponse(restApiId: String)
