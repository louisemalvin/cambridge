use anyhow::Result;
use clap::Parser;
use tracing_subscriber::EnvFilter;

mod cli;
mod discovery;
mod output;
mod preview;
mod runtime;
mod ui;

use cli::Cli;

fn main() -> Result<()> {
    let cli = Cli::parse();
    init_logging(&cli.log_level)?;
    ui::run(cli);
    Ok(())
}

fn init_logging(level: &str) -> Result<()> {
    let filter = EnvFilter::try_new(level)?;
    tracing_subscriber::fmt().with_env_filter(filter).try_init().ok();
    Ok(())
}
