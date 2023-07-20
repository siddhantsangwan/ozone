/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.hdds.scm.container.placement.algorithms;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.scm.ContainerPlacementStatus;
import org.apache.hadoop.hdds.scm.SCMCommonPlacementPolicy;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.net.NetworkTopology;
import org.apache.hadoop.hdds.scm.net.Node;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.pipeline.PipelineStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.hadoop.hdds.scm.exceptions.SCMException.ResultCodes.FAILED_TO_FIND_SUITABLE_NODE;

/**
 * Container placement policy that scatter datanodes on different racks
 * , together with the space to satisfy the size constraints.
 * <p>
 * This placement policy will try to distribute datanodes on as many racks as
 * possible.
 * <p>
 * This implementation applies to network topology like "/rack/node". Don't
 * recommend to use this if the network topology has more layers.
 * <p>
 */
public final class SCMContainerPlacementRackScatter
    extends SCMCommonPlacementPolicy {
  @VisibleForTesting
  public static final Logger LOG =
      LoggerFactory.getLogger(SCMContainerPlacementRackScatter.class);
  private final NetworkTopology networkTopology;
  private static final int RACK_LEVEL = 1;
  // OUTER_LOOP is to avoid infinity choose on all racks
  private static final int OUTER_LOOP_MAX_RETRY = 3;
  // INNER_LOOP is to choose node in each rack
  private static final int INNER_LOOP_MAX_RETRY = 5;
  private final SCMContainerPlacementMetrics metrics;
  /*
  If fallback is true, then chooseDatanodesInternal API will try to return the
  required number of nodes even if placement policy is not satisfied. Still,
  it'll try to follow the policy as well as possible.
  */
  private boolean fallback;

  /**
   * Constructs a Container Placement with rack awareness.
   *
   * @param nodeManager Node Manager
   * @param conf Configuration
   */
  public SCMContainerPlacementRackScatter(final NodeManager nodeManager,
      final ConfigurationSource conf, final NetworkTopology networkTopology,
      boolean fallback, final SCMContainerPlacementMetrics metrics) {
    super(nodeManager, conf);
    this.networkTopology = networkTopology;
    this.metrics = metrics;
    this.fallback = fallback;
  }

  /**
   * Constructor for Pipeline Provider Pipeline Placement with rack awareness.
   * @param nodeManager Node Manager
   * @param stateManager State Manager
   * @param conf Configuration
   */
  public SCMContainerPlacementRackScatter(NodeManager nodeManager,
      PipelineStateManager stateManager, ConfigurationSource conf) {
    super(nodeManager, conf);
    this.networkTopology = nodeManager.getClusterNetworkTopologyMap();
    this.metrics = null;
  }

  @SuppressWarnings("checkstyle:parameternumber")
  private Set<DatanodeDetails> chooseNodesFromRacks(List<Node> racks,
      List<Node> unavailableNodes,
      List<DatanodeDetails> mutableFavoredNodes,
      int nodesRequired, long metadataSizeRequired, long dataSizeRequired,
      int maxOuterLoopIterations, Map<Node, Integer> rackCntMap,
      int maxReplicasPerRack) {
    if (nodesRequired <= 0) {
      return Collections.emptySet();
    }
    List<Node> toChooseRacks = new LinkedList<>();
    Set<DatanodeDetails> chosenNodes = new LinkedHashSet<>();
    Set<Node> skippedRacks = new HashSet<>();
    // If the result doesn't change after retryCount, we return with exception
    int retryCount = 0;
    while (nodesRequired > 0 && maxOuterLoopIterations > 0) {
      if (retryCount > OUTER_LOOP_MAX_RETRY) {
        return chosenNodes;
      }
      int chosenListSize = chosenNodes.size();

      // Refill toChooseRacks, we put skippedRacks in front of toChooseRacks
      // for an even distribution
      for (Node rack : racks) {
        if (rackCntMap.getOrDefault(rack, 0) < maxReplicasPerRack) {
          toChooseRacks.add(rack);
        }
      }
      if (!skippedRacks.isEmpty()) {
        toChooseRacks.removeAll(skippedRacks);
        toChooseRacks.addAll(0, skippedRacks);
        skippedRacks.clear();
      }

      if (mutableFavoredNodes.size() > 0) {
        List<DatanodeDetails> chosenFavoredNodesInForLoop = new ArrayList<>();
        for (DatanodeDetails favoredNode : mutableFavoredNodes) {
          Node curRack = getRackOfDatanodeDetails(favoredNode);
          if (toChooseRacks.contains(curRack)) {
            chosenNodes.add(favoredNode);
            rackCntMap.merge(curRack, 1, Math::addExact);
            toChooseRacks.remove(curRack);
            chosenFavoredNodesInForLoop.add(favoredNode);
            unavailableNodes.add(favoredNode);
            nodesRequired--;
            if (nodesRequired == 0) {
              break;
            }
          }
        }
        mutableFavoredNodes.removeAll(chosenFavoredNodesInForLoop);
      }

      // If satisfied by favored nodes, return then.
      if (nodesRequired == 0) {
        break;
      }

      for (Node rack : toChooseRacks) {
        if (rack == null) {
          // TODO: need to recheck why null coming here.
          continue;
        }
        Node node = chooseNode(rack.getNetworkFullPath(), unavailableNodes,
                metadataSizeRequired, dataSizeRequired);
        if (node != null) {
          chosenNodes.add((DatanodeDetails) node);
          rackCntMap.merge(rack, 1, Math::addExact);
          mutableFavoredNodes.remove(node);
          unavailableNodes.add(node);
          nodesRequired--;
          if (nodesRequired == 0) {
            break;
          }
        } else {
          // Store the skipped racks to check them first in next outer loop
          skippedRacks.add(rack);
        }
      }
      // Clear toChooseRacks for this loop
      toChooseRacks.clear();

      // If chosenNodes not changed, increase the retryCount
      if (chosenListSize == chosenNodes.size()) {
        retryCount++;
      } else {
        // If chosenNodes changed, reset the retryCount
        retryCount = 0;
      }
      maxOuterLoopIterations--;
    }
    return chosenNodes;
  }

  /**
   * Called by SCM to choose datanodes.
   *
   * @param usedNodes - list of the datanodes to already chosen in the
   *                      pipeline.
   * @param excludedNodes - list of the datanodes to exclude.
   * @param favoredNodes - list of nodes preferred. This is a hint to the
   *                     allocator, whether the favored nodes will be used
   *                     depends on whether the nodes meets the allocator's
   *                     requirement.
   * @param nodesRequired - number of datanodes required.
   * @param dataSizeRequired - size required for the container.
   * @param metadataSizeRequired - size required for Ratis metadata.
   * @return List of datanodes.
   * @throws SCMException  SCMException
   */
  @Override
  @SuppressWarnings("checkstyle:methodlength")
  protected List<DatanodeDetails> chooseDatanodesInternal(
          List<DatanodeDetails> usedNodes,
          final List<DatanodeDetails> excludedNodes,
          final List<DatanodeDetails> favoredNodes,
          final int nodesRequired, final long metadataSizeRequired,
          final long dataSizeRequired) throws SCMException {
    if (nodesRequired <= 0) {
      String errorMsg = "num of nodes required to choose should bigger" +
          "than 0, but the given num is " + nodesRequired;
      throw new SCMException(errorMsg, null);
    }
    if (metrics != null) {
      metrics.incrDatanodeRequestCount(nodesRequired);
    }
    int excludedNodesCount = excludedNodes == null ? 0 : excludedNodes.size();
    int usedNodesCount = usedNodes == null ? 0 : usedNodes.size();
    List<Node> availableNodes = networkTopology.getNodes(
        networkTopology.getMaxLevel());
    int totalNodesCount = availableNodes.size();
    if (excludedNodes != null) {
      availableNodes.removeAll(excludedNodes);
    }
    if (usedNodes != null) {
      availableNodes.removeAll(usedNodes);
    }
    if (availableNodes.size() < nodesRequired) {
      throw new SCMException("No enough datanodes to choose. " +
          "TotalNodes = " + totalNodesCount +
          " AvailableNodes = " + availableNodes.size() +
          " RequiredNodes = " + nodesRequired +
          " ExcludedNodes = " + excludedNodesCount +
          " UsedNodes = " + usedNodesCount,
          FAILED_TO_FIND_SUITABLE_NODE);
    }
    List<DatanodeDetails> mutableFavoredNodes = new ArrayList<>();
    if (favoredNodes != null) {
      // Generate mutableFavoredNodes, only stores valid favoredNodes
      for (DatanodeDetails datanodeDetails : favoredNodes) {
        if (isValidNode(datanodeDetails, metadataSizeRequired,
            dataSizeRequired)) {
          mutableFavoredNodes.add(datanodeDetails);
        }
      }
      Collections.shuffle(mutableFavoredNodes);
    }
    if (excludedNodes != null) {
      mutableFavoredNodes.removeAll(excludedNodes);
    }
    if (usedNodes == null) {
      usedNodes = Collections.emptyList();
    }
    List<Node> racks = getAllRacks();
    // usedRacksCntMap maps a rack to the number of usedNodes it contains
    Map<Node, Integer> usedRacksCntMap = new HashMap<>();
    for (Node node : usedNodes) {
      Node rack = networkTopology.getAncestor(node, RACK_LEVEL);
      if (rack != null) {
        usedRacksCntMap.merge(rack, 1, Math::addExact);
      }
    }
    int requiredReplicationFactor = usedNodes.size() + nodesRequired;
    int numberOfRacksRequired = getRequiredRackCount(requiredReplicationFactor);
    int additionalRacksRequired =
            numberOfRacksRequired - usedRacksCntMap.size();
    LOG.debug("Additional nodes required: {}. Additional racks required: {}.",
        nodesRequired, additionalRacksRequired);
    if (nodesRequired < additionalRacksRequired) {
      String reason = "Required nodes size: " + nodesRequired
              + " is less than required number of racks to choose: "
              + additionalRacksRequired + ".";
      LOG.warn("Placement policy cannot choose the enough racks. {}"
                      + "Total number of Required Racks: {} Used Racks Count:" +
                      " {}, Required Nodes count: {}, fallback: {}.",
              reason, numberOfRacksRequired, usedRacksCntMap.size(),
              nodesRequired, fallback);

      /*
      The number of additional Datanodes ("nodesRequired") that were asked for
      are less than the additional number of racks ("additionalRacksRequired")
      that container replicas need to be on. This means that returning
      nodesRequired number of nodes will not satisfy the rack scatter policy.
      If fallback is false, throw an exception. Else, continue to find
      nodesRequired number of nodes.
       */
      if (!fallback) {
        throw new SCMException(reason,
            FAILED_TO_FIND_SUITABLE_NODE);
      }
    }
    int maxReplicasPerRack = getMaxReplicasPerRack(requiredReplicationFactor,
            numberOfRacksRequired);
    LOG.debug("According to required replication factor: {}, and total number" +
            " of racks required: {}, max replicas per rack is {}.",
        requiredReplicationFactor, numberOfRacksRequired, maxReplicasPerRack);
    // For excluded nodes, we sort their racks at rear
    racks = sortRackWithExcludedNodes(racks, excludedNodes, usedRacksCntMap);

    List<Node> unavailableNodes = new ArrayList<>(usedNodes);
    if (excludedNodes != null) {
      unavailableNodes.addAll(excludedNodes);
    }

    LOG.debug("Available racks excluding racks with used nodes: {}.", racks);
    if (racks.size() < additionalRacksRequired) {
      String reason = "Number of existing racks: " + racks.size()
              + "is less than additional required number of racks to choose: "
              + additionalRacksRequired + " do not match.";
      LOG.warn("Placement policy cannot choose the enough racks. {}"
                      + "Total number of Required Racks: {} Used Racks Count:" +
                      " {}, Required Nodes count: {}, fallback: {}.",
              reason, numberOfRacksRequired, usedRacksCntMap.size(),
              nodesRequired, fallback);

      /*
      The number of racks which aren't already used by existing replicas
      ("racks") and hence are available for new replicas are less than the
      additional number of racks that replicas need to be on
      ("additionalRacksRequired").
      */
      if (!fallback) {
        throw new SCMException(reason,
            FAILED_TO_FIND_SUITABLE_NODE);
      }
    }

    /*
    If fallback is false, and we reach here, then nodesRequired is greater
    than or equal to additionalRacksRequired. So we need to ask for
    nodesRequired number of nodes. If fallback is true, then nodesRequired
    could be less than additionalRacksRequired. We still need to ask for
    nodesRequired number of more nodes, even if that won't satisfy the
    requirement of being on additionalRacksRequired more racks.

    So, in both cases, we want to ask for nodesRequired number of nodes.
    */
    Set<DatanodeDetails> chosenNodes = new LinkedHashSet<>(
        chooseNodesFromRacks(racks, unavailableNodes,
            mutableFavoredNodes, nodesRequired, metadataSizeRequired,
            dataSizeRequired, maxReplicasPerRack, usedRacksCntMap,
            maxReplicasPerRack));

    if (chosenNodes.size() < additionalRacksRequired) {
      String reason = "Chosen nodes size from Unique Racks: " + chosenNodes
              .size() + ", but required nodes to choose from Unique Racks: "
              + additionalRacksRequired + " do not match.";
      LOG.warn("Placement policy could not choose the enough nodes from " +
                      "available racks. {} Available racks count: {},"
                      + " Excluded nodes count: {}, UsedNodes count: {}",
              reason, racks.size(), excludedNodesCount, usedNodesCount);

      /*
      Couldn't choose enough racks to satisfy the rack scatter policy. If
      fallback is not allowed, then throw an exception.
      */
      if (!fallback) {
        throw new SCMException(reason,
            SCMException.ResultCodes.FAILED_TO_FIND_HEALTHY_NODES);
      }
    }

    if (chosenNodes.size() < nodesRequired) {
      /* If fallback is allowed, adjust maxReplicasToMove such that
      replication factor number of replicas are allowed per rack.
      */
      if (fallback) {
        maxReplicasPerRack = requiredReplicationFactor;
      }
      racks.addAll(usedRacksCntMap.keySet());
      racks = sortRackWithExcludedNodes(racks, excludedNodes, usedRacksCntMap);
      racks.addAll(usedRacksCntMap.keySet());
      LOG.debug("Available racks considering racks with used and exclude " +
              "nodes: {}.", racks);
      chosenNodes.addAll(chooseNodesFromRacks(racks, unavailableNodes,
              mutableFavoredNodes, nodesRequired - chosenNodes.size(),
              metadataSizeRequired, dataSizeRequired,
              nodesRequired - chosenNodes.size(),
              usedRacksCntMap, maxReplicasPerRack));
    }
    List<DatanodeDetails> result = new ArrayList<>(chosenNodes);
    if (nodesRequired != chosenNodes.size()) {
      String reason = "Chosen nodes size: " + chosenNodes
              .size() + ", but required nodes to choose: "
              + nodesRequired + " do not match.";
      LOG.warn("Placement policy could not choose enough nodes."
               + " {} Available nodes count: {}, Excluded nodes count: {}, "
               + "Used nodes count: {}",
              reason, totalNodesCount, excludedNodesCount, usedNodesCount);
      throw new SCMException(reason,
              SCMException.ResultCodes.FAILED_TO_FIND_HEALTHY_NODES);
    }

    List<DatanodeDetails> newPlacement =
        new ArrayList<>(usedNodes.size() + result.size());
    newPlacement.addAll(usedNodes);
    newPlacement.addAll(chosenNodes);
    ContainerPlacementStatus placementStatus =
        validateContainerPlacement(newPlacement, requiredReplicationFactor);
    if (!placementStatus.isPolicySatisfied()) {
      ContainerPlacementStatus initialPlacementStatus =
          validateContainerPlacement(usedNodes, requiredReplicationFactor);
      if (initialPlacementStatus.misReplicationCount()
              < placementStatus.misReplicationCount()) {
        String errorMsg = "ContainerPlacementPolicy not met. Misreplication" +
                " Reason: " + placementStatus.misReplicatedReason() +
                " Initial Used nodes mis-replication Count: " +
                initialPlacementStatus.misReplicationCount() +
                " Used nodes + Chosen nodes mis-replication Count: " +
                placementStatus.misReplicationCount();
        throw new SCMException(errorMsg, FAILED_TO_FIND_SUITABLE_NODE);
      }
    }
    LOG.info("Chosen nodes: {}. isPolicySatisfied: {}. fallback: {}.", result,
        placementStatus.isPolicySatisfied(), fallback);
    return result;
  }

  @Override
  public DatanodeDetails chooseNode(List<DatanodeDetails> healthyNodes) {
    return null;
  }

  /**
   * Choose a datanode which meets the requirements. If there is no node which
   * meets all the requirements, there is fallback chosen process depending on
   * whether fallback is allowed when this class is instantiated.
   *
   *
   * @param scope - the rack we are searching nodes under
   * @param excludedNodes - list of the datanodes to excluded. Can be null.
   * @param dataSizeRequired - size required for the container.
   * @param metadataSizeRequired - size required for Ratis metadata.
   * @return the chosen datanode.
   */
  private Node chooseNode(String scope, List<Node> excludedNodes,
      long metadataSizeRequired, long dataSizeRequired) {
    int maxRetry = INNER_LOOP_MAX_RETRY;
    while (true) {
      if (metrics != null) {
        metrics.incrDatanodeChooseAttemptCount();
      }
      Node node = null;
      try {
        node = networkTopology.chooseRandom(scope, excludedNodes);
      } catch (Exception e) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Error while choosing Node: Scope: {}, Excluded Nodes: " +
                          "{}, MetaDataSizeRequired: {}, DataSizeRequired: " +
                  "{}", scope, excludedNodes, metadataSizeRequired,
                  dataSizeRequired, e);
        }
      }
      if (node != null) {
        DatanodeDetails datanodeDetails = (DatanodeDetails) node;
        if (isValidNode(datanodeDetails, metadataSizeRequired,
            dataSizeRequired)) {
          if (metrics != null) {
            metrics.incrDatanodeChooseSuccessCount();
          }
          return node;
        }
        // exclude the unavailable node for the following retries.
        excludedNodes.add(node);
      } else {
        LOG.debug("Failed to find the datanode for container. excludedNodes: " +
            "{}, rack {}", excludedNodes, scope);
      }
      maxRetry--;
      if (maxRetry == 0) {
        // avoid the infinite loop
        LOG.info("No satisfied datanode to meet the constraints. "
            + "Metadatadata size required: {} Data size required: {}, scope "
            + "{}, excluded nodes {}",
            metadataSizeRequired, dataSizeRequired, scope, excludedNodes);
        return null;
      }
    }
  }

  /**
   * For EC placement policy, desired rack count would be equal to the num of
   * Replicas.
   * @param numReplicas - num of Replicas.
   * @return required rack count.
   */
  @Override
  protected int getRequiredRackCount(int numReplicas) {
    if (networkTopology == null) {
      return 1;
    }
    int maxLevel = networkTopology.getMaxLevel();
    int numRacks = networkTopology.getNumOfNodes(maxLevel - 1);
    // Return the num of Rack if numRack less than numReplicas
    return Math.min(numRacks, numReplicas);
  }

  private Node getRackOfDatanodeDetails(DatanodeDetails datanodeDetails) {
    String location = datanodeDetails.getNetworkLocation();
    return networkTopology.getNode(location);
  }

  /**
   * For the rack holding excludedNodes, we prefer not to choose from these
   * racks, so we sort these racks at rear.
   * @param racks
   * @param excludedNodes
   * @param usedRacks
   * @return racks that are present in the specified racks, excluding racks
   * that are present in the specified usedRacks
   */
  private List<Node> sortRackWithExcludedNodes(List<Node> racks,
          List<DatanodeDetails> excludedNodes, Map<Node, Integer> usedRacks) {
    if ((excludedNodes == null || excludedNodes.isEmpty())
            && usedRacks.isEmpty()) {
      return racks;
    }
    Set<Node> lessPreferredRacks = new HashSet<>();
    for (Node node : excludedNodes) {
      Node rack = networkTopology.getAncestor(node, RACK_LEVEL);
      // Dead Nodes have been removed from the topology and so have a
      // null rack. We need to exclude those from the rack list.
      if (rack != null && !usedRacks.containsKey(rack)) {
        lessPreferredRacks.add(rack);
      }
    }
    List <Node> result = new ArrayList<>();
    for (Node rack : racks) {
      if (!usedRacks.containsKey(rack) && !lessPreferredRacks.contains(rack)) {
        result.add(rack);
      }
    }
    result.addAll(lessPreferredRacks);
    return result;
  }

  private List<Node> getAllRacks() {
    int rackLevel = networkTopology.getMaxLevel() - 1;
    List<Node> racks = networkTopology.getNodes(rackLevel);
    Collections.shuffle(racks);
    return racks;
  }

  @VisibleForTesting
  void setFallback(boolean fallback) {
    this.fallback = fallback;
  }

}
