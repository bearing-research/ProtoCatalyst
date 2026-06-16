use datafusion::prelude::SessionContext;
use datafusion_flight_sql_server::service::FlightSqlService;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr = "0.0.0.0:50051";
    println!("Starting DataFusion Flight SQL server on {addr}");

    let ctx = SessionContext::new();
    FlightSqlService::new(ctx.state())
        .serve(addr.to_string())
        .await?;

    Ok(())
}
