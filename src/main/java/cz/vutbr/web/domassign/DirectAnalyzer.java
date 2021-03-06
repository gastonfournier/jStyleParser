/**
 * SimpleAnalyzer.java
 *
 * Created on 6.6.2012, 13:57:00 by burgetr
 */
package cz.vutbr.web.domassign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import cz.vutbr.web.css.CSSFactory;
import cz.vutbr.web.css.CombinedSelector;
import cz.vutbr.web.css.Declaration;
import cz.vutbr.web.css.MatchCondition;
import cz.vutbr.web.css.MediaSpec;
import cz.vutbr.web.css.NodeData;
import cz.vutbr.web.css.RuleSet;
import cz.vutbr.web.css.Selector;
import cz.vutbr.web.css.Selector.PseudoDeclaration;
import cz.vutbr.web.css.StyleSheet;
import cz.vutbr.web.csskit.ElementUtil;

/**
 * A simple ananalyzer that computes a style for the individual DOM nodes with no mapping and caching.
 * This analyzer is suitable for obtaining the style of individual elements without computing the style
 * for the whole DOM tree. However, in larger scale, the performance of the individual computation
 * is significantly worse.  
 * 
 * @author burgetr
 */
public class DirectAnalyzer extends Analyzer
{
    private static final Logger log = LoggerFactory.getLogger(DirectAnalyzer.class);

    /**
     * Creates the analyzer for a single style sheet.
     * @param sheet The stylesheet that will be used as the source of rules.
     */
    public DirectAnalyzer(StyleSheet sheet)
    {
        super(sheet);
    }

    /**
     * Creates the analyzer for multiple style sheets.
     * @param sheets A list of stylesheets that will be used as the source of rules.
     */
    public DirectAnalyzer(List<StyleSheet> sheets)
    {
        super(sheets);
    }

    /**
     * Computes the style of an element with an eventual pseudo element for the given media.
     * @param el The DOM element.
     * @param pseudo A pseudo element that should be used for style computation or <code>null</code> if no pseudo element should be used (e.g. :after).
     * @param media Used media specification.
     * @return The relevant declarations from the registered style sheets.
     */
    public NodeData getElementStyle(Element el, PseudoDeclaration pseudo, MediaSpec media)
    {
        if (rules == null)
            classifyAllSheets(media);
        
        List<Declaration> decls = getDeclarationsForElement(el, pseudo, rules);
        
        NodeData main = CSSFactory.createNodeData();
        for (Declaration d : decls)
            main.push(d);
        
        return main;
    }
    
    /**
     * Computes the style of an element with an eventual pseudo element for the given media.
     * @param el The DOM element.
     * @param pseudo A pseudo element that should be used for style computation or <code>null</code> if no pseudo element should be used (e.g. :after).
     * @param media Used media name (e.g. "screen" or "all")
     * @return The relevant declarations from the registered style sheets.
     */
    public NodeData getElementStyle(Element el, PseudoDeclaration pseudo, String media)
    {
        return getElementStyle(el, pseudo, new MediaSpec(media));
    }
    
    //==========================================================================================
    
    protected List<Declaration> getDeclarationsForElement(Element e, PseudoDeclaration pseudo, Holder holder) 
    {
        if(log.isDebugEnabled()) {
            log.debug("Traversal of {} {}.", e.getNodeName(), e.getNodeValue());
        }

        // create set of possible candidates applicable to given element
        // set is automatically filtered to not contain duplicates
        Set<OrderedRule> candidates = new HashSet<OrderedRule>();

        // match element classes
        for (String cname : ElementUtil.elementClasses(e)) {
            // holder contains rule with given class
            List<OrderedRule> rules = holder.get(HolderItem.CLASS, cname.toLowerCase());
            if (rules != null)
                candidates.addAll(rules);
        }
        log.trace("After CLASSes {} total candidates.", candidates.size());

        // match IDs
        String id = ElementUtil.elementID(e);
        if (id != null && id.length() != 0) {
            List<OrderedRule> rules = holder.get(HolderItem.ID, id.toLowerCase());
            if (rules != null)
                candidates.addAll(rules);
        }
        log.trace("After IDs {} total candidates.", candidates.size());
        
        // match elements
        String name = ElementUtil.elementName(e);
        if (name != null) {
            List<OrderedRule> rules = holder.get(HolderItem.ELEMENT, name.toLowerCase());
            if (rules != null)
                candidates.addAll(rules);
        }
        log.trace("After ELEMENTs {} total candidates.", candidates.size());

        // others
        candidates.addAll(holder.get(HolderItem.OTHER, null));

        // transform to list to speed up traversal
        // and sort rules in order as they were found in CSS definition
        List<OrderedRule> clist = new ArrayList<OrderedRule>(candidates);
        Collections.sort(clist);
        
        log.debug("Totally {} candidates.", candidates.size());
        log.trace("With values: {}", clist);

        // resulting list of declaration for this element with no pseudo-selectors (main list)(local cache)
        List<Declaration> eldecl = new ArrayList<Declaration>();
        
        // for all candidates
        for (OrderedRule orule : clist) {
            
            final RuleSet rule = orule.getRule();
            StyleSheet sheet = rule.getStyleSheet();
            StyleSheet.Origin origin = (sheet == null) ? StyleSheet.Origin.AGENT : sheet.getOrigin();
            
            // for all selectors inside
            for (CombinedSelector s : rule.getSelectors()) {
                
                if (!matchSelector(s, e)) {
                    log.trace("CombinedSelector \"{}\" NOT matched!", s);
                    continue;
                }

                log.trace("CombinedSelector \"{}\" matched", s);
                
                PseudoDeclaration psel = s.getPseudoElement();
                CombinedSelector.Specificity spec = s.computeSpecificity();
                if (psel == pseudo)
                {
                    // add to the resulting list
                    for (Declaration d : rule)
                        eldecl.add(new AssignedDeclaration(d, spec, origin));
                }
            }
        }

        // sort declarations
        Collections.sort(eldecl); //sort the main list
        log.debug("Sorted {} declarations.", eldecl.size());
        log.trace("With values: {}", eldecl);
        
        return eldecl;
    }

    private boolean nodeSelectorMatches(final Selector s, final Node n) {
        if (n.getNodeType() == Node.ELEMENT_NODE) {
            final Element e = (Element) n;
            final MatchCondition matchCond = this.getMatchCondition();
            return matchCond == null ? s.matches(e) : s.matches(e, matchCond);
        } else {
            return false;
        }
    }
    
    protected boolean matchSelector(CombinedSelector sel, Element e)
    {
        boolean retval = false;
        Selector.Combinator combinator = null;
        Element current = e;
        // traverse simple selector backwards
        for (int i = sel.size() - 1; i >= 0; i--) {
            // last simple selector
            Selector s = sel.get(i);
            log.trace("Iterating loop with selector {}, combinator {}",
                    s, combinator);

            // decide according to combinator anti-pattern
            if (combinator == null) {
                retval = this.elementSelectorMatches(s, current);
            } else if (combinator == Selector.Combinator.ADJACENT) {
                Node adjacent = current;
                do {
                    adjacent = adjacent.getPreviousSibling();
                } while (adjacent != null && adjacent.getNodeType() != Node.ELEMENT_NODE);
                retval = false;
                if (adjacent != null && adjacent.getNodeType() == Node.ELEMENT_NODE)
                {
                    current = (Element) adjacent; 
                    retval = this.elementSelectorMatches(s, current);
                }
            } else if (combinator == Selector.Combinator.PRECEDING) {
                Node preceding = current.getPreviousSibling();
                retval = false;
                do
                {
                    if (preceding != null)
                    {
                        if (this.nodeSelectorMatches(s, preceding))
                        {
                            current = (Element) preceding;
                            retval = true;
                        }
                        else
                            preceding = preceding.getPreviousSibling();
                    }
                } while (!retval && preceding != null);
            } else if (combinator == Selector.Combinator.DESCENDANT) {
                Node ancestor = current.getParentNode();
                retval = false;
                do
                {
                    if (ancestor != null)
                    {
                        if (this.nodeSelectorMatches(s, ancestor))
                        {
                            current = (Element) ancestor;
                            retval = true;
                        }
                        else
                            ancestor = ancestor.getParentNode();
                    }
                } while (!retval && ancestor != null);
            } else if (combinator == Selector.Combinator.CHILD) {
                Node parent = current.getParentNode();
                retval = false;
                if (parent != null && parent.getNodeType() == Node.ELEMENT_NODE)
                {
                    current = (Element) parent;
                    retval = this.elementSelectorMatches(s, current);
                }
            }

            // set combinator for next loop
            combinator = s.getCombinator();

            // leave loop if not matched
            if (retval == false)
                break;
        }
        return retval;
    }
    
    
}
