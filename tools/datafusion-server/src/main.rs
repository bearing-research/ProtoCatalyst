use std::pin::Pin;

use arrow_flight::flight_service_server::{FlightService, FlightServiceServer};
use arrow_flight::{
    Action, Criteria, Empty, FlightData, FlightDescriptor, FlightInfo, HandshakeRequest,
    HandshakeResponse, PollInfo, SchemaResult, Ticket,
};
use datafusion::prelude::SessionContext;
use datafusion_flight_sql_server::service::FlightSqlService;
use futures::{stream, Stream};
use tonic::transport::Server;
use tonic::{Request, Response, Status, Streaming};

/// Wraps the DataFusion `FlightSqlService` to answer `Handshake` with a no-op success.
///
/// `datafusion-flight-sql-server` intentionally returns `Unimplemented` for handshake ("favor
/// middleware over handshake"), but the Arrow ADBC Flight SQL driver performs an *unconditional*
/// handshake when it builds its client and treats the failure as fatal. Since this is a stateless,
/// auth-less dev server, returning an empty `HandshakeResponse` lets the client proceed; every other
/// method is delegated unchanged to the inner service.
struct HandshakeShim {
    inner: FlightSqlService,
}

#[tonic::async_trait]
impl FlightService for HandshakeShim {
    type HandshakeStream =
        Pin<Box<dyn Stream<Item = Result<HandshakeResponse, Status>> + Send + 'static>>;
    type ListFlightsStream = <FlightSqlService as FlightService>::ListFlightsStream;
    type DoGetStream = <FlightSqlService as FlightService>::DoGetStream;
    type DoPutStream = <FlightSqlService as FlightService>::DoPutStream;
    type DoExchangeStream = <FlightSqlService as FlightService>::DoExchangeStream;
    type DoActionStream = <FlightSqlService as FlightService>::DoActionStream;
    type ListActionsStream = <FlightSqlService as FlightService>::ListActionsStream;

    async fn handshake(
        &self,
        _request: Request<Streaming<HandshakeRequest>>,
    ) -> Result<Response<Self::HandshakeStream>, Status> {
        let resp = HandshakeResponse::default();
        let s = stream::once(async move { Ok(resp) });
        Ok(Response::new(Box::pin(s)))
    }

    async fn list_flights(
        &self,
        request: Request<Criteria>,
    ) -> Result<Response<Self::ListFlightsStream>, Status> {
        self.inner.list_flights(request).await
    }

    async fn get_flight_info(
        &self,
        request: Request<FlightDescriptor>,
    ) -> Result<Response<FlightInfo>, Status> {
        self.inner.get_flight_info(request).await
    }

    async fn poll_flight_info(
        &self,
        request: Request<FlightDescriptor>,
    ) -> Result<Response<PollInfo>, Status> {
        self.inner.poll_flight_info(request).await
    }

    async fn get_schema(
        &self,
        request: Request<FlightDescriptor>,
    ) -> Result<Response<SchemaResult>, Status> {
        self.inner.get_schema(request).await
    }

    async fn do_get(
        &self,
        request: Request<Ticket>,
    ) -> Result<Response<Self::DoGetStream>, Status> {
        self.inner.do_get(request).await
    }

    async fn do_put(
        &self,
        request: Request<Streaming<FlightData>>,
    ) -> Result<Response<Self::DoPutStream>, Status> {
        self.inner.do_put(request).await
    }

    async fn do_exchange(
        &self,
        request: Request<Streaming<FlightData>>,
    ) -> Result<Response<Self::DoExchangeStream>, Status> {
        self.inner.do_exchange(request).await
    }

    async fn do_action(
        &self,
        request: Request<Action>,
    ) -> Result<Response<Self::DoActionStream>, Status> {
        self.inner.do_action(request).await
    }

    async fn list_actions(
        &self,
        request: Request<Empty>,
    ) -> Result<Response<Self::ListActionsStream>, Status> {
        self.inner.list_actions(request).await
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr = "0.0.0.0:50051".parse()?;
    println!("Starting DataFusion Flight SQL server on {addr}");

    let ctx = SessionContext::new();
    let inner = FlightSqlService::new(ctx.state());
    let service = FlightServiceServer::new(HandshakeShim { inner });

    Server::builder().add_service(service).serve(addr).await?;

    Ok(())
}
