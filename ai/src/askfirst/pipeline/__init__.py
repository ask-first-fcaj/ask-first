"""Pipeline factory (plan §3.5). `pipeline/` chỉ được import `askfirst.domain` và thư viện ngoài."""
import pathlib

from askfirst.domain.models import ArtifactStore, KVCache, Pipeline
from askfirst.pipeline.stub import StubPipeline


def build_pipeline(impl: str, config_dir: pathlib.Path, artifacts: ArtifactStore, url_cache: KVCache) -> Pipeline:
    """impl = "stub" | "real". Gọi MỘT lần lúc cold start; object dùng lại giữa các lần gọi."""
    if impl == "stub":
        return StubPipeline()
    if impl == "real":
        raise NotImplementedError("RealPipeline: INT-01")
    raise ValueError(f"PIPELINE_IMPL lạ: {impl!r}")
