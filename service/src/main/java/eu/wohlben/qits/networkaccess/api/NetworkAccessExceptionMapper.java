package eu.wohlben.qits.networkaccess.api;

import eu.wohlben.qits.networkaccess.api.NetworkAccessWire.ErrorBody;
import eu.wohlben.qits.networkaccess.control.NetworkAccessException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** The domain's refusals as 400, 403, 404, 409 and 503, each with an {@link ErrorBody}. */
@Provider
public class NetworkAccessExceptionMapper implements ExceptionMapper<NetworkAccessException> {

  @Override
  public Response toResponse(NetworkAccessException refusal) {
    Response.Status status =
        switch (refusal.kind()) {
          case BAD_REQUEST -> Response.Status.BAD_REQUEST;
          case FORBIDDEN -> Response.Status.FORBIDDEN;
          case NOT_FOUND -> Response.Status.NOT_FOUND;
          case CONFLICT -> Response.Status.CONFLICT;
          case UNAVAILABLE -> Response.Status.SERVICE_UNAVAILABLE;
        };
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(new ErrorBody(refusal.getMessage()))
        .build();
  }
}
