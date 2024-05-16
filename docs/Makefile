# Minimal makefile for Sphinx documentation
#

# You can set these variables from the command line, and also
# from the environment for the first two.
SPHINXOPTS    ?=
SPHINXBUILD   ?= sphinx-build
SOURCEDIR     = source
BUILDDIR      = build

# Create symbolic links to the subdirectories of /docs to the /docs/source directory
# to include all the markdown files in the documentation
#TODO: if this code stays then make the dir variable a list of all subfodirs of /doc except for /doc/source

pushd $(SOURCEDIR)

	if not exist "user_guide" ( 
		ln -sf  "..\README.md" "user_guide.md" 
	) 
	
	dirs=("getting started" "rdf4j" "corese-python" "federation" "storage" "advanced")

	for dir in "${dirs[@]}"; do
		if [ ! -d "$dir" ]; then
			ln -sf "../$dir" "$dir"
		fi
	done

popd

# Put it first so that "make" without argument is like "make help".
help:
	@$(SPHINXBUILD) -M help "$(SOURCEDIR)" "$(BUILDDIR)" $(SPHINXOPTS) $(O)

.PHONY: help Makefile 

# Catch-all target: route all unknown targets to Sphinx using the new
# "make mode" option.  $(O) is meant as a shortcut for $(SPHINXOPTS).
%: Makefile
	@$(SPHINXBUILD) -M $@ "$(SOURCEDIR)" "$(BUILDDIR)" $(SPHINXOPTS) $(O)

