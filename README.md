# A Framework for Automated Block-Test Generation

## Repository structure
| Directory               | Purpose                                       |
| ------------------------| --------------------------------------------- |
| blockgen                | BlockSmith (block-test generation framework)  |
| data                    | Raw data                                      |
| Docker                  | Scripts to run our experiments in Docker      |
| extension               | BlockSmith's Maven extension                  |
| scripts                 | Our infrastructure                            |
| libs                    | Library dependencies                          |

## Projects and data
You can find the 394 fragments that we evaluate [here](data/fragments.csv) and the 900 fragments [here](data/rv/rv_fragments.txt). You can find the full UpSet plot (RQ3) [here](data/mutations.pdf) and the full fragment statistics [here](data/stats/fragment-stats.csv).

## Usage
### Prerequisites:
- A x86-64 architecture machine
- Ubuntu 22.04
- [Docker](https://docs.docker.com/get-docker/)

### Setup
First, you need to pull the Docker image, which contains this repository along with all necessary dependencies already installed. Run the following command in the terminal.
```sh
docker pull blocksmithframework/blocksmith
```

Next, you should clone this repository.
```sh
git clone https://github.com/block-smith-framework/block-smith
```

### Running BlockSmith experiments
```sh
# Enter the following commands **outside the Docker container** and inside the current repository directory
cd Docker

mkdir -p mutants

# Choose whether you want to run BlockSmith on one project (less than an hour) or all projects.
cp $(pwd)/allprojects.csv $(pwd)/projects.csv # Use all projects
cp $(pwd)/oneproject.csv $(pwd)/projects.csv # Use a single project
# You need to run one of the above commands before running each of the following three experiments.

# Run all in parallel experiment
bash run_multiple_pipeline_in_docker.sh -s EXTs,CONs,EXTr,CONrc,INd,INs,CONc,INr $(pwd)/projects.csv output-parallel $(pwd)/mutants

# Run sequentially until coverage stabilizes experiment
bash run_multiple_coverage_pipeline_in_docker.sh -s EXTs,CONs,EXTr,CONrc,INd,INs,CONc,INr $(pwd)/projects.csv output-by_coverage $(pwd)/mutants

# run sequentially until mutation score stabilizes experiment
bash run_multiple_mutation_pipeline_in_docker.sh -p 0 -s EXTs,CONs,EXTr,CONrc,INd,INs,CONc,INr $(pwd)/projects.csv output-by_mutants $(pwd)/mutants
```

After running the experiments, you can find the outputs in `{output-parallel,output-by_coverage,output-by_mutants}/<fragment_id>`. There, you will see the logs in `docker-<strategy>.log` and the generated block tests in `output-<strategy>/data/blocktest-r0.txt`. You can find the block tests after reduction in `reduction/blocktest-r2.txt`.
